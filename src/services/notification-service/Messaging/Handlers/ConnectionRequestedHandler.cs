using System.Text.Json;
using notification_service.Events;
using notification_service.Repositories.Interfaces;
using notification_service.Services;

namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S): this class's only job is "turn one
// connection.requested event into a RequestReceived notification for the
// recipient" — it does not decide SignalR-vs-FCM itself (INotificationDispatcher's
// job, Dependency Inversion SOLID D) and never calls back to Connection
// Service to check whether this request is still pending (see the class-level
// "Event staleness" note below).
//
// Pattern: Observer (via events) — connection.requested is published ONCE by
// Connection Service, which has never heard of this handler. This is the
// FIRST real subscriber to this topic (see T049's own "why this ticket
// exists") — before this ticket, nothing consumed it at all.
public class ConnectionRequestedHandler : IConnectionRequestedHandler
{
    private readonly IProcessedEventRepository _processedEvents;
    private readonly INotificationDispatcher _dispatcher;
    private readonly ILogger<ConnectionRequestedHandler> _logger;

    public ConnectionRequestedHandler(
        IProcessedEventRepository processedEvents,
        INotificationDispatcher dispatcher,
        ILogger<ConnectionRequestedHandler> logger)
    {
        _processedEvents = processedEvents;
        _dispatcher = dispatcher;
        _logger = logger;
    }

    public async Task HandleAsync(string rawMessage, CancellationToken cancellationToken)
    {
        // Guard Clause: a null-value Kafka record (tombstone, or a malformed
        // produce) is routine input a consumer must tolerate, not a crash.
        if (string.IsNullOrWhiteSpace(rawMessage))
        {
            _logger.LogWarning("Received empty connection.requested message, skipping.");
            return;
        }

        ConnectionRequestedEvent? evt;
        try
        {
            evt = JsonSerializer.Deserialize<ConnectionRequestedEvent>(rawMessage);
        }
        catch (JsonException ex)
        {
            // Log and skip, never throw — a thrown exception here would
            // propagate to ConnectionRequestedConsumer, which would then
            // never commit this offset, so Kafka would redeliver this exact
            // unparseable message forever. Only the exception, never the raw
            // payload, is logged (same policy as chat-service's handler).
            _logger.LogError(ex, "Failed to deserialize connection.requested message, skipping.");
            return;
        }

        // Guard Clause: reject an event missing/zeroed in a field this
        // handler cannot function without, before using any of them.
        if (evt is null || evt.EventId == Guid.Empty || evt.ConnectionId <= 0 || evt.RequesterId <= 0 || evt.RecipientId <= 0)
        {
            _logger.LogError(
                "Received connection.requested event with a missing/invalid required field (EventId={EventId}, ConnectionId={ConnectionId}), skipping.",
                evt?.EventId, evt?.ConnectionId);
            return;
        }

        var eventId = evt.EventId.ToString();

        // Idempotency (kafka-outbox skill): checked BEFORE dispatching any
        // notification — Kafka's at-least-once delivery means this exact
        // event_id can arrive again (e.g. this process crashes after
        // dispatching but before ConnectionRequestedConsumer commits the
        // offset); without this guard a redelivery would push the same
        // "new request" alert to the recipient twice.
        if (await _processedEvents.ExistsAsync(eventId))
        {
            _logger.LogInformation("Skipping already-processed connection.requested event {EventId}.", eventId);
            return;
        }

        // Event staleness after an outage (kafka-outbox skill, "Event
        // staleness after an outage"): if a Kafka backlog outlives this
        // request's 10-minute TTL, Connection Service may have already moved
        // this connection to EXPIRED by the time this message arrives. This
        // handler deliberately does NOT call back to Connection Service to
        // check "is this still pending" first — doing so would reintroduce
        // the tight coupling Kafka exists to remove. The notification fires
        // from the event payload alone; the recipient's own next action
        // (opening the request) hits Connection Service directly and sees
        // the real, current status.
        await _dispatcher.DispatchAsync(
            evt.RecipientId,
            "RequestReceived",
            "New connection request",
            "Someone nearby wants to connect with you.",
            new Dictionary<string, string> { ["connectionId"] = evt.ConnectionId.ToString() },
            cancellationToken);

        // Recorded LAST, only after the notification was dispatched — same
        // trade-off every other consumer in this codebase already accepts
        // (see chat-service's ConnectionAcceptedHandler comment): a crash
        // between dispatch and this line means a redelivery notifies again,
        // which is a harmless duplicate push, not an incorrect one.
        await _processedEvents.RecordAsync(eventId);
    }
}
