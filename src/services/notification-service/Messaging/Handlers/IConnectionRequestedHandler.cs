namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D) — same
// split as chat-service's IConnectionAcceptedHandler: ConnectionRequestedConsumer
// (Kafka mechanics: subscribe/poll/commit) depends on this abstraction, never
// on the concrete handler, so the two concerns can be tested independently.
public interface IConnectionRequestedHandler
{
    // Raw JSON string, not an already-deserialized event — a malformed/
    // poison-pill message is something THIS method must log-and-skip, not
    // something the consumer has to catch (see
    // ConnectionRequestedHandler's own comment).
    Task HandleAsync(string rawMessage, CancellationToken cancellationToken);
}
