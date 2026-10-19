package com.shareconnectsave.rating.kafka.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the processed_events table from
// V003__rating_service_consumer_tables.sql.
//
// Pattern: Idempotency ledger - ChatClosedConsumer, RatingSubmittedConsumer,
// and ReportFiledConsumer all check
// ProcessedEventRepository.existsById(eventId) BEFORE doing any of their own
// work, and save a row here only AFTER that work (the closed_chats insert,
// or the trust score recalculation) has already happened. Kafka's
// at-least-once delivery guarantee means the same event_id can be handed to
// this consumer group twice - without this table, a redelivered
// rating.submitted could recalculate (and re-publish trust.score.updated
// for) the same user twice over, which is wasted work at best and a
// misleading extra event on the wire at worst. Same shape as
// connection-service's kafka/domain/ProcessedEvent (T033) and
// discovery-service's own (T025) - deliberately NOT reusing either class
// directly, since Database per Service means each service's idempotency
// ledger lives in that service's own schema, not a shared library table.
//
// eventId is a String, not java.util.UUID: this table only ever needs
// byte-for-byte equality against the event_id Jackson already deserialized
// as a String off each event record, never a UUID-typed SQL operation - same
// trade-off connection-service's ProcessedEvent documents.
@Entity
@Table(name = "processed_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    // DB-assigned default (SYSUTCDATETIME()), same convention as every other
    // *_at column in this service (Rating.createdAt, OutboxEvent.createdAt) -
    // reflects when this row actually committed, not this JVM's clock.
    @Column(name = "processed_at", insertable = false, updatable = false)
    private Instant processedAt;
}
