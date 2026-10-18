package com.shareconnectsave.rating.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// Maps onto the outbox table from V002__rating_service_outbox_table.sql.
//
// Outbox Pattern: this is the FIRST outbox table in rating-service - T041
// only built the ratings/trust_scores tables, with no Kafka-producing code
// at all. This row is written inside the SAME @Transactional method that
// inserts the Rating row (see RatingServiceImpl.submitRating), so either
// both the rating and this outbox row commit together, or neither does - a
// crash between "save the rating" and "publish rating.submitted" can never
// leave a rating that nobody downstream ever learns about. Deliberately
// built as a straight port of connection-service's own outbox/ package
// (T032) rather than a new design - the SAME mechanism, scaled down to one
// service's own schema, is what lets T044's trust.score.updated producer
// reuse this exact IOutboxService/OutboxRelay pair with zero new wiring.
//
// Lombok: @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor, not
// @Data - same reasoning as every other @Entity in this codebase: @Data's
// generated equals()/hashCode() over every field breaks JPA's identity and
// lazy-loading assumptions.
@Entity
@Table(name = "outbox")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    // UUID, app-assigned (no @GeneratedValue) rather than a DB IDENTITY
    // column: OutboxServiceImpl.publish mints this id with
    // UUID.fromString(eventId) BEFORE the row exists, reusing the SAME value
    // the caller already baked into the event payload's own event_id field -
    // that is what lets downstream consumers (Idempotency) dedup on
    // event_id and have it double as this outbox row's primary key. An
    // IDENTITY column can't be known until after INSERT, so it could never
    // serve that second purpose.
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "topic", nullable = false)
    private String topic;

    // Kafka ordering is only guaranteed WITHIN a partition, and the
    // partition a message lands on is derived from this key - never left
    // null. RatingServiceImpl keys by connection_id (the aggregate the
    // rating.submitted event is about), matching connection-service's own
    // "partition by the aggregate the event describes" convention.
    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    // JSON string, not a JPA-mapped object graph: OutboxRelay only ever
    // needs to hand this value, byte-for-byte, to KafkaTemplate.send() - it
    // never queries into individual fields of it, so there is nothing to
    // gain from modelling its structure in this table and a real cost (a
    // schema migration every time an event's shape changes) to doing so.
    @Column(name = "payload", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String payload;

    // Enumerated(STRING), not ORDINAL - a string column survives
    // OutboxStatus ever gaining a new constant without relabeling existing
    // rows, same reasoning as TrustScore.badgeLevel/requestLimit.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    // insertable/updatable = false: the DDL's own SYSUTCDATETIME() default
    // is the DB's ground truth for "when did this outbox row actually
    // commit" - same convention as Rating.createdAt. OutboxRelay's polling
    // query orders by this column, so it must reflect actual commit order,
    // not app-server clock time that can drift under GC pause or clock skew
    // across replicas.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // Null until OutboxRelay successfully hands this row to Kafka - set
    // together with status flipping to PROCESSED (see markProcessed below).
    @Column(name = "processed_at")
    private Instant processedAt;

    // Tell, Don't Ask (encapsulation) - OutboxRelay asks this row to mark
    // itself processed rather than reaching in with raw setters and
    // recomputing processedAt by hand at its one call site.
    public void markProcessed(Instant processedAt) {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = processedAt;
    }
}
