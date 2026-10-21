namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D) — see
// IConnectionRequestedHandler's identical comment. A SEPARATE interface (and
// implementation) from chat-service's own IConnectionAcceptedHandler — same
// topic, two independent subscribers (Observer, GoF): chat-service opens a
// room, this service sends a notification, and neither needs to know the
// other exists.
public interface IConnectionAcceptedHandler
{
    Task HandleAsync(string rawMessage, CancellationToken cancellationToken);
}
