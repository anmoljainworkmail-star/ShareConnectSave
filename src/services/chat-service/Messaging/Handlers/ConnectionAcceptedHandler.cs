using System.Text.Json;
using chat_service.Dtos;
using chat_service.Events;
using chat_service.Hubs;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;
using Microsoft.AspNetCore.SignalR;

namespace chat_service.Messaging.Handlers;

// Single Responsibility (SOLID S): this class's only job is "turn this one
// connection.accepted event into a room + a notification" - it does not
// implement chat lifecycle/closing logic (ICloseChatRoomService's job) and
// does not publish any outbound Kafka event itself (see the "what NOT to do"
// note below). See IConnectionAcceptedHandler's comment for why parsing
// lives here rather than in the consumer.
//
// Pattern: Observer (via events) - connection.accepted is published ONCE by
// Connection Service, which has no idea this class exists. This handler and
// Notification Service's own (future) consumer of the same topic both react
// independently; neither needs to coordinate with the other, and Connection
// Service needs to know about neither.
public class ConnectionAcceptedHandler : IConnectionAcceptedHandler
{
    private readonly IProcessedEventRepository _processedEvents;
    private readonly IChatRoomService _chatRoomService;
    private readonly IHubContext<ChatHub> _hubContext;
    private readonly ILogger<ConnectionAcceptedHandler> _logger;

    public ConnectionAcceptedHandler(
        IProcessedEventRepository processedEvents,
        IChatRoomService chatRoomService,
        IHubContext<ChatHub> hubContext,
        ILogger<ConnectionAcceptedHandler> logger)
    {
        _processedEvents = processedEvents;
        _chatRoomService = chatRoomService;
        _hubContext = hubContext;
        _logger = logger;
    }

    public async Task HandleAsync(string rawMessage, CancellationToken cancellationToken)
    {
        // Guard Clause: a null-value Kafka record (a tombstone, or a
        // malformed produce) is routine input a consumer must tolerate, not
        // a crash.
        if (string.IsNullOrWhiteSpace(rawMessage))
        {
            _logger.LogWarning("Received empty connection.accepted message, skipping.");
            return;
        }

        ConnectionAcceptedEvent? evt;
        try
        {
            evt = JsonSerializer.Deserialize<ConnectionAcceptedEvent>(rawMessage);
        }
        catch (JsonException ex)
        {
            // Log and skip, never throw - same poison-pill reasoning as
            // connection-service's TrustScoreUpdatedConsumer: a thrown
            // exception here would propagate up to ConnectionAcceptedConsumer,
            // which would then never commit this offset - Kafka would
            // redeliver this exact unparseable message forever. Only the
            // exception message is logged, never the raw payload, in case a
            // future schema revision adds a sensitive field.
            _logger.LogError(ex, "Failed to deserialize connection.accepted message, skipping.");
            return;
        }

        // Guard Clause: reject an event missing/zeroed in a field this
        // handler cannot function without, before using any of them - a
        // null EventId would be meaningless as an idempotency key, and a
        // zero connection/user id would silently open a nonsense room.
        if (evt is null || evt.EventId == Guid.Empty || evt.ConnectionId <= 0 || evt.RequesterId <= 0 || evt.RecipientId <= 0)
        {
            // Log only parsed, known-safe fields instead of raw payload, same
            // policy as the JSON-deserialization path above (lines 60-68).
            _logger.LogError(
                "Received connection.accepted event with a missing/invalid required field (EventId={EventId}, ConnectionId={ConnectionId}), skipping.",
                evt?.EventId, evt?.ConnectionId);
            return;
        }

        var eventId = evt.EventId.ToString();

        // Idempotency (kafka-outbox skill): checked BEFORE opening a room or
        // sending any notification, same check-then-act-then-record shape as
        // connection-service's TrustScoreUpdatedConsumer and discovery-
        // service's UserVerifiedEventListener. Kafka's at-least-once
        // delivery means this exact event_id can arrive again (e.g. this
        // process crashes after opening the room but before
        // ConnectionAcceptedConsumer commits the offset) - without this
        // guard, a redelivery would create a second chat_room document for a
        // connection that already has one.
        if (await _processedEvents.ExistsAsync(eventId))
        {
            _logger.LogInformation("Skipping already-processed connection.accepted event {EventId}.", eventId);
            return;
        }

        var room = await _chatRoomService.OpenRoomAsync(evt.ConnectionId, evt.RequesterId, evt.RecipientId);

        await NotifyBothUsersAsync(room.Id!, evt, cancellationToken);

        // Recorded LAST, only after the room exists and both notifications
        // were sent - if this process crashes between opening the room and
        // reaching this line, the redelivered event will NOT find this
        // event_id recorded yet and will attempt the whole thing again. That
        // reprocessing is not actually safe against creating a second room
        // (see IChatRoomService's comment - this handler does not re-check
        // "does a room already exist for this connection", by design, to
        // avoid a second idempotency mechanism alongside processed_events),
        // but it is the same trade-off every other consumer in this codebase
        // already accepts: Kafka partition ordering plus single-partition,
        // single-consumer-at-a-time delivery within a group makes true
        // concurrent redelivery rare, not the routine case this check exists
        // to prevent.
        await _processedEvents.RecordAsync(eventId);
    }

    // Pattern: Observer (via events), fan-out leg two of two - connection.
    // accepted was published once; this method is Chat Service's OWN further
    // fan-out, turning that one event into two independent SignalR sends,
    // one per participant's personal group. Keyed by user id (`user:{id}`),
    // not chat id (`chat:{id}`, see SignalRGroupNames) - a client has never
    // heard of this chatId before this exact moment, so it cannot have
    // already joined a per-chat group for it; the personal group is what
    // lets this notification reach a client that is only identified by its
    // own user id (see ChatHub.OnConnectedAsync).
    private async Task NotifyBothUsersAsync(string chatId, ConnectionAcceptedEvent evt, CancellationToken cancellationToken)
    {
        var notification = new ChatOpenedNotification(chatId, evt.ConnectionId.ToString());

        await _hubContext.Clients.Group(SignalRGroupNames.User(evt.RequesterId))
            .SendAsync("ChatOpened", notification, cancellationToken);

        await _hubContext.Clients.Group(SignalRGroupNames.User(evt.RecipientId))
            .SendAsync("ChatOpened", notification, cancellationToken);
    }
}
