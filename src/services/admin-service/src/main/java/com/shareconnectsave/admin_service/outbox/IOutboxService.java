package com.shareconnectsave.admin_service.outbox;

// Dependency Inversion (SOLID-D): ReviewQueueServiceImpl depends on THIS
// interface, never on KafkaTemplate directly and never on
// AdminOutboxServiceImpl's concrete class — Architecture Rule #1 ("never call
// KafkaTemplate directly from business code") is enforced simply by there
// being no other path to Kafka business code can reach for.
//
// Liskov Substitution (SOLID-L), the exact example CLAUDE.md itself names for
// this project: "IOutboxService — any implementation (SQL, MongoDB,
// in-memory for tests) must behave identically to callers." rating-service's
// and connection-service's IOutboxService both back this same interface
// shape with a SQL outbox table; this one backs it with a single Mongo
// collection (see AdminOutboxServiceImpl's own comment for why) — a caller
// of publish() cannot tell, and must never need to tell, which one it's
// talking to.
public interface IOutboxService {

    // Same contract as rating-service's/connection-service's IOutboxService:
    // partitionKey is supplied by the CALLER (here, the reinstated user's
    // id) — only the business code calling publish() knows what its own
    // aggregate id is. eventId is the UUID string already baked into the
    // event payload record (TrustScoreUpdatedEvent.eventId), reused as this
    // outbox row's own id so a future consumer could deduplicate via that
    // same event_id (Idempotency groundwork).
    void publish(String topic, String partitionKey, String eventId, Object payload);
}
