namespace notification_service.Messaging.Handlers;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D) — see
// IConnectionRequestedHandler's identical comment.
public interface IConnectionExpiredHandler
{
    Task HandleAsync(string rawMessage, CancellationToken cancellationToken);
}
