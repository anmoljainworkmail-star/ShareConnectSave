namespace user_service.Events;

using System.Text.Json;
using Confluent.Kafka;
using user_service.Models;

// Single Responsibility (SOLID S): this class only knows how to serialize a
// UserVerifiedEvent and hand it to Kafka's client library — it has no
// opinion on WHEN a user counts as verified (that decision lives entirely in
// OtpService.VerifyOtpAsync, which calls this class only after it has
// already decided).
//
// This is the ticket-approved exception to this project's usual "Kafka
// producers write to an outbox table, never call IProducer<> directly"
// rule (see kafka-outbox skill / CLAUDE.md's Outbox Pattern row). There is
// no outbox table backing this yet — that is Phase 14's job (T091-T096).
// Until then, a dropped event here is only recoverable by a human replaying
// it manually from the structured error log this class writes on failure.
public class KafkaUserVerifiedEventPublisher : IUserVerifiedEventPublisher
{
    private const string Topic = "user.verified";

    private readonly IProducer<string, string> _producer;
    private readonly ILogger<KafkaUserVerifiedEventPublisher> _logger;

    public KafkaUserVerifiedEventPublisher(IProducer<string, string> producer, ILogger<KafkaUserVerifiedEventPublisher> logger)
    {
        _producer = producer;
        _logger = logger;
    }

    public void PublishUserVerified(User user, DateTime verifiedAt)
    {
        var userId = user.Id.ToString();

        var payload = new UserVerifiedEvent(
            EventId: Guid.NewGuid(),
            UserId: userId,
            // The schema's gender enum is lowercase (female/male/unspecified);
            // User.Gender is stored PascalCase (Female/Male/Unspecified) — this
            // is the one place the two conventions get reconciled, so every
            // consumer only ever sees the schema's casing.
            Gender: user.Gender.ToLowerInvariant(),
            VerifiedAt: verifiedAt);

        var json = JsonSerializer.Serialize(payload);

        // Fire-and-forget with background retry (this ticket's headline
        // pattern): Produce() is a LOCAL, synchronous call that only enqueues
        // the message into librdkafka's internal buffer and returns — it does
        // NOT wait for the broker to acknowledge the write. That is what makes
        // it safe to call from inside an HTTP request handler (OtpService.
        // VerifyOtpAsync) without blocking the caller's response on Kafka's
        // availability or network latency.
        //
        // Contrast with ProduceAsync(): that method returns a Task which only
        // completes once the broker has acknowledged the write (or the send
        // has definitively failed) — awaiting it here would reintroduce the
        // exact "HTTP request blocked on Kafka" failure mode this ticket
        // exists to avoid. librdkafka (the native library Confluent.Kafka
        // wraps) already retries transient send failures internally, in the
        // background, before ever invoking the delivery-report callback below
        // with a final outcome — so no hand-rolled retry loop is needed to
        // satisfy "retries in the background".
        //
        // user_id is the partition key: every event for the same user lands
        // on the same partition, which guarantees Kafka only preserves
        // ordering WITHIN one user's own events, not across users — the
        // correct ordering guarantee for a per-user fact like "this user
        // became verified".
        _producer.Produce(
            Topic,
            new Message<string, string> { Key = userId, Value = json },
            deliveryReport =>
            {
                if (deliveryReport.Error.IsError)
                {
                    // Structured logging on publish failure: service name,
                    // topic, user_id, and the exception reason are all
                    // captured here because — with no outbox table backing
                    // this producer — this log line is the ONLY record that a
                    // user.verified event was ever lost. Without this, the
                    // failure is invisible: the HTTP request already
                    // succeeded (by design, per this ticket's scope), so
                    // nothing else would ever surface it.
                    _logger.LogError(
                        "Failed to publish {Event} to Kafka topic {Topic} for user {UserId}: {Reason}",
                        nameof(UserVerifiedEvent),
                        Topic,
                        userId,
                        deliveryReport.Error.Reason);
                }
            });
    }

    // No IDisposable here: IProducer<string,string> is itself registered as
    // its own singleton in Program.cs (see the DI registration comment
    // there), so the DI container disposes THAT instance directly at
    // shutdown — including its internal flush of any buffered messages.
    // Disposing it a second time from here would be redundant, not safer.
}
