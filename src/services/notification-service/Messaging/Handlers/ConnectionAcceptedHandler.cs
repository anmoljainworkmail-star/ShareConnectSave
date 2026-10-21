using System.Text.Json;
using notification_service.Events;
using notification_service.Repositories.Interfaces;
using notification_service.Services;

namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S): this class's only job is "turn one
// connection.accepted event into a RequestAccepted notification for the
// REQUESTER" (the recipient already knows they just accepted — see the
// ticket's own "Technical specification" table). It never calls back to
// Connection Service to re-check status first — same staleness reasoning as
// ConnectionRequestedHandler.
//
// Pattern: Observer (via events), fan-out leg TWO of two for this topic —
// chat-service's own ConnectionAcceptedHandler (a completely separate class,
// in a separate .NET project, with no shared code) opens a chat room from
// this exact same event; this class independently turns it into a push.
// Neither subscriber is aware of the other, which is the entire point of
// publishing a past-tense fact instead of issuing two separate commands.
public class ConnectionAcceptedHandler : IConnectionAcceptedHandler
{
    private readonly IProcessedEventRepository _processedEvents;
    private readonly INotificationDispatcher _dispatcher;
    private readonly ILogger<ConnectionAcceptedHandler> _logger;

    public ConnectionAcceptedHandler(
        IProcessedEventRepository processedEvents,
        INotificationDispatcher dispatcher,
        ILogger<ConnectionAcceptedHandler> logger)
    {
        _processedEvents = processedEvents;
        _dispatcher = dispatcher;
        _logger = logger;
    }

    public async Task HandleAsync(string rawMessage, CancellationToken cancellationToken)
    {
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
            _logger.LogError(ex, "Failed to deserialize connection.accepted message, skipping.");
            return;
        }

        if (evt is null || evt.EventId == Guid.Empty || evt.ConnectionId <= 0 || evt.RequesterId <= 0 || evt.RecipientId <= 0)
        {
            _logger.LogError(
                "Received connection.accepted event with a missing/invalid required field (EventId={EventId}, ConnectionId={ConnectionId}), skipping.",
                evt?.EventId, evt?.ConnectionId);
            return;
        }

        var eventId = evt.EventId.ToString();

        if (await _processedEvents.ExistsAsync(eventId))
        {
            _logger.LogInformation("Skipping already-processed connection.accepted event {EventId}.", eventId);
            return;
        }

        await _dispatcher.DispatchAsync(
            evt.RequesterId,
            "RequestAccepted",
            "Request accepted",
            "Your connection request was accepted. Chat is now open.",
            new Dictionary<string, string> { ["connectionId"] = evt.ConnectionId.ToString() },
            cancellationToken);

        await _processedEvents.RecordAsync(eventId);
    }
}
