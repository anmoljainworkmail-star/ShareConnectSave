namespace user_service.Events;

using user_service.Models;

// Dependency Inversion (SOLID D): OtpService (the caller) depends on this
// abstraction, never on Confluent.Kafka's IProducer<> directly. That is what
// lets the real outbox-backed implementation (Phase 14, T091-T096) swap in
// later without touching the status-transition code path in OtpService —
// the exact same shape IUserRepository/ITwilioClient already give it.
//
// Single Responsibility (SOLID S): this interface's only job is "announce a
// user just got verified" — it says nothing about HOW (Kafka topic name,
// partition key, retry policy). Those are KafkaUserVerifiedEventPublisher's
// concern alone.
public interface IUserVerifiedEventPublisher
{
    // Fire-and-forget from the caller's perspective: this method enqueues the
    // publish and returns without waiting on Kafka's network round trip (see
    // KafkaUserVerifiedEventPublisher's class comment for why). It deliberately
    // returns void, not Task — there is nothing for OtpService to await here,
    // and awaiting a Task that only resolves once the message is queued (not
    // delivered) would wrongly suggest there's something meaningful to wait for.
    void PublishUserVerified(User user, DateTime verifiedAt);
}
