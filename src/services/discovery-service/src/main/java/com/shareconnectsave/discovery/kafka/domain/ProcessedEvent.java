package com.shareconnectsave.discovery.kafka.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the processed_events table created by V003__processed_events.sql.
//
// Pattern: Idempotency ledger — this is the dedup key both T025 listeners
// check via ProcessedEventsRepository.existsById(eventId) BEFORE doing any
// Redis side effect, and write to only AFTER that side effect succeeds.
// Kafka's at-least-once delivery guarantee means the same event_id can be
// handed to this consumer group twice (e.g. this JVM crashes after the SADD
// below but before the container commits the offset) — without this row,
// the redelivery would silently repeat whatever side effect already
// happened. Repeating a Set add is harmless on its own, but the row is what
// makes that a deliberate, verifiable guarantee instead of a lucky accident
// of Redis Set semantics, and it is the same shape every other consumer in
// this codebase (see kafka-outbox skill) is expected to use.
//
// eventId is a String, not java.util.UUID: see UserVerifiedEvent's own
// comment on the same trade-off — every producer today happens to emit a
// real UUID string, but this table only ever needs byte-for-byte equality,
// never a UUID-typed SQL operation, so there is nothing to gain from a
// stricter type and a JDBC-mapping mismatch to potentially lose.
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

    // DB-assigned default (SYSUTCDATETIME()), same convention as
    // ScanSession.startedAt — reflects when the row actually committed, not
    // when this JVM built the object.
    @Column(name = "processed_at", insertable = false, updatable = false)
    private Instant processedAt;
}
