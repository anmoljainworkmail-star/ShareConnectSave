package com.shareconnectsave.rating.outbox;

// Dependency Inversion (SOLID-D): RatingServiceImpl depends on THIS
// interface, never on KafkaTemplate directly and never on OutboxServiceImpl's
// concrete class. Architecture Rule #1 ("Kafka publishing goes through
// outbox table in same DB transaction - never call KafkaTemplate directly
// from business code") is enforced simply by there being no other path to
// Kafka that business code can reach for.
//
// Liskov Substitution (SOLID-L): this ticket's SQL-outbox-table
// implementation (OutboxServiceImpl), an in-memory fake for a unit test, or
// a shared-java-lib version some later ticket replaces it with must all
// behave identically from a caller's point of view: "the payload is
// durably queued for eventual publish, keyed for partition ordering",
// nothing more specific than that.
public interface IOutboxService {

    // partitionKey is supplied by the CALLER (e.g. connectionId.toString()),
    // never derived inside the outbox layer or OutboxRelay - only the
    // business code calling publish() knows what its own aggregate id is,
    // and Kafka only guarantees ordering within the partition that key
    // resolves to.
    //
    // eventId is the UUID string already baked into the event payload
    // record (e.g. RatingSubmittedEvent.eventId) - the same value must be
    // reused as this outbox row's id so downstream consumers can
    // deduplicate via that event_id (Idempotency). The caller, not the
    // outbox layer, knows what eventId to use because it already minted one
    // when constructing the event record moments before calling this method.
    void publish(String topic, String partitionKey, String eventId, Object payload);
}
