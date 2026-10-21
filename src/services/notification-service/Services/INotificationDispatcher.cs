namespace notification_service.Services;

// Dependency Inversion (SOLID D): every Kafka consumer handler
// (ConnectionRequestedHandler, ConnectionAcceptedHandler,
// ConnectionExpiredHandler) depends on THIS interface, never directly on
// IHubContext<NotificationHub> or IFcmNotificationService. The "pick SignalR
// if the user is connected, else fall back to FCM" decision is business
// logic that belongs in exactly one testable place (Single Responsibility),
// not duplicated three times across the handlers that happen to need it.
//
// eventName ("RequestReceived" / "RequestAccepted" / "RequestExpired") is a
// plain string, not an enum: the three Kafka consumers already know their
// own event's name as a Kafka-topic-adjacent constant, so this keeps the
// dispatcher itself Open/Closed (SOLID O) — a FOURTH future event type needs
// zero changes here, only a new caller.
public interface INotificationDispatcher
{
    Task DispatchAsync(
        long userId,
        string eventName,
        string title,
        string body,
        IDictionary<string, string> data,
        CancellationToken cancellationToken);
}
