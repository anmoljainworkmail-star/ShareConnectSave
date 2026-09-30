package com.shareconnectsave.connection.kafka.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the processed_events table created by
// V005__processed_events.sql.
//
// Pattern: Idempotency ledger — TrustScoreUpdatedConsumer checks
// ProcessedEventsRepository.existsById(eventId) BEFORE touching
// RequestLimitCache, and writes to this table only AFTER that cache update
// succeeds. Kafka's at-least-once delivery guarantee means the same
// event_id can be handed to this consumer group twice (e.g. this JVM
// crashes after RequestLimitCache.update() but before the container commits
// the offset) — without this row, the redelivery would silently re-apply
// the same request_limit. Re-applying an identical int is harmless on its
// own, but this row is what makes that a deliberate, verifiable guarantee
// instead of a lucky accident of Map.put being naturally idempotent — the
// same shape discovery-service's kafka/domain/ProcessedEvent (T025) uses,
// and every consumer in this codebase is expected to follow (kafka-outbox
// skill).
//
// eventId is a String, not java.util.UUID: same trade-off as
// discovery-service's ProcessedEvent — every producer today happens to emit
// a real UUID string, but this table only ever needs byte-for-byte
// equality, never a UUID-typed SQL operation.
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
    // ConnectionRequest.createdAt — reflects when the row actually
    // committed, not when this JVM built the object.
    @Column(name = "processed_at", insertable = false, updatable = false)
    private Instant processedAt;
}
