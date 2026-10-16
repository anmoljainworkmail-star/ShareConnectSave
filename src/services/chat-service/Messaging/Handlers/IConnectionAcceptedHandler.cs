namespace chat_service.Messaging.Handlers;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D): this
// interface's only job is "turn one connection.accepted Kafka message into a
// chat room and a notification" - ConnectionAcceptedConsumer (the
// BackgroundService that actually polls Kafka) depends on this abstraction,
// never on the concrete handler, so the Kafka-mechanics class
// (subscribe/poll/commit) and the business-logic class (deserialize/
// idempotency/open room/notify) can be tested independently of one another.
// Same split as the dotnet-mvc-controllers skill's own worked example.
public interface IConnectionAcceptedHandler
{
    // Takes the raw JSON string straight off the topic, not an already-
    // deserialized ConnectionAcceptedEvent - deserialization failure (a
    // malformed/poison-pill message) is itself something this handler must
    // log-and-skip rather than throw, so parsing has to happen inside the
    // method whose job is deciding what "handled" means, see
    // ConnectionAcceptedHandler's own comment.
    Task HandleAsync(string rawMessage, CancellationToken cancellationToken);
}
