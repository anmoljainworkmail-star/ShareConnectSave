using System.Text.Json;
using notification_service.Events;
using notification_service.Repositories.Interfaces;
using notification_service.Services;

namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S): this class's only job is "turn one
// connection.expired event into a RequestExpired notification for the
// requester" — the ONLY user this event's own schema even names (no
// recipient_id field exists on contracts/kafka/connection.expired.schema.json;
// see ConnectionExpiredEvent's own comment).
//
// Saga Compensation (kafka-outbox skill, system design table): this event is
// the compensating branch of the connection lifecycle saga (request -> accept
// OR expire). Per the ticket's "Known follow-ups" note, a Kafka outage long
// enough to outlive a request's 10-minute TTL can cause Connection Service's
// outbox to queue BOTH connection.requested and connection.expired for the
// same connection, relayed back-to-back once Kafka recovers — this handler
// treats that as entirely expected, never as a reason to call back to
// Connection Service first.
public class ConnectionExpiredHandler : IConnectionExpiredHandler
{
    private readonly IProcessedEventRepository _processedEvents;
    private readonly INotificationDispatcher _dispatcher;
    private readonly ILogger<ConnectionExpiredHandler> _logger;

    public ConnectionExpiredHandler(
        IProcessedEventRepository processedEvents,
        INotificationDispatcher dispatcher,
        ILogger<ConnectionExpiredHandler> logger)
    {
        _processedEvents = processedEvents;
        _dispatcher = dispatcher;
        _logger = logger;
    }

    public async Task HandleAsync(string rawMessage, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(rawMessage))
        {
            _logger.LogWarning("Received empty connection.expired message, skipping.");
            return;
        }

        ConnectionExpiredEvent? evt;
        try
        {
            evt = JsonSerializer.Deserialize<ConnectionExpiredEvent>(rawMessage);
        }
        catch (JsonException ex)
        {
            _logger.LogError(ex, "Failed to deserialize connection.expired message, skipping.");
            return;
        }

        // Guard Clause — note there is no RecipientId to validate here at
        // all (see ConnectionExpiredEvent's own comment): this schema never
        // carries one.
        if (evt is null || evt.EventId == Guid.Empty || evt.ConnectionId <= 0 || evt.RequesterId <= 0)
        {
            _logger.LogError(
                "Received connection.expired event with a missing/invalid required field (EventId={EventId}, ConnectionId={ConnectionId}), skipping.",
                evt?.EventId, evt?.ConnectionId);
            return;
        }

        var eventId = evt.EventId.ToString();

        if (await _processedEvents.ExistsAsync(eventId))
        {
            _logger.LogInformation("Skipping already-processed connection.expired event {EventId}.", eventId);
            return;
        }

        await _dispatcher.DispatchAsync(
            evt.RequesterId,
            "RequestExpired",
            "Request expired",
            "Your connection request expired without a response.",
            new Dictionary<string, string> { ["connectionId"] = evt.ConnectionId.ToString() },
            cancellationToken);

        await _processedEvents.RecordAsync(eventId);
    }
}
