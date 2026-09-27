package com.shareconnectsave.connection.outbox;

// Dependency Inversion (SOLID-D): every future Kafka producer in this
// service (this ticket's acceptConnection, and whatever Phase 5/6 tickets
// add later) depends on THIS interface, never on KafkaTemplate directly and
// never on OutboxServiceImpl's concrete class. Architecture Rule #1
// ("Kafka publishing goes through outbox table in same DB transaction —
// never call KafkaTemplate directly from business code") is enforced simply
// by there being no other path to Kafka that business code can reach for.
//
// Liskov Substitution (SOLID-L): any implementation of this interface —
// this ticket's SQL-outbox-table version, an in-memory fake for a unit
// test, or a future MongoDB-backed version for a document-DB service — must
// behave identically from a caller's point of view: "the payload is durably
// queued for eventual publish", nothing more specific than that.
public interface IOutboxService {

    void publish(String topic, Object payload);
}
