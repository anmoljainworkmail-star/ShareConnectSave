package com.shareconnectsave.connection.outbox;

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

// Maps onto the outbox table from V002__outbox_and_saga_state.sql, as
// widened by V004__outbox_partition_key_and_relay.sql (T032).
//
// Outbox Pattern: this is the FIRST outbox table in the codebase (Connection
// Service is the first ticket to need it) - every other Kafka-touching
// service so far (discovery-service's kafka/domain/ProcessedEvent) only
// ever consumed events, never produced one. A row here is written inside
// the SAME @Transactional method that flips connection_requests.status to
// ACCEPTED (see ConnectionServiceImpl.acceptConnection), so either both the
// status change and this row commit together, or neither does - a crash
// between "update the DB" and "publish to Kafka" can never leave a
// half-finished saga step, because nothing has actually tried to reach
// Kafka yet at that point. The row sits here as durable, committed proof of
// intent; OutboxRelay (T032) is the only component that reads PENDING rows
// back out and hands them to KafkaTemplate.
//
// Lombok: @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor, not
// @Data - same reasoning as every other @Entity in this codebase (see
// ConnectionRequest's class comment): @Data's generated equals()/hashCode()
// over every field breaks JPA's identity and lazy-loading assumptions.
@Entity
@Table(name = "outbox")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    // UUID, app-assigned (no @GeneratedValue) rather than a DB IDENTITY
    // column: OutboxServiceImpl.publish mints this id with
    // UUID.randomUUID() BEFORE the row exists, which is what lets this same
    // value double as the event's own event_id for downstream consumer
    // dedup (Idempotency groundwork) - an IDENTITY column can't be known
    // until after INSERT, so it could never serve that second purpose.
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "topic", nullable = false)
    private String topic;

    // Kafka ordering is only guaranteed WITHIN a partition, and the
    // partition a message lands on is derived from this key - never left
    // null. Every producer in this service keys by connection_id (its
    // aggregate id) so that, e.g., a connection's own accepted/expired
    // events can never be reordered relative to each other, even though
    // ordering ACROSS different connection ids is never promised or needed.
    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    // JSON string, not a JPA-mapped object graph: OutboxRelay only ever
    // needs to hand this value, byte-for-byte, to KafkaTemplate.send() - it
    // never queries into individual fields of it, so there is nothing to
    // gain from modelling its structure in this table and a real cost (a
    // schema migration every time an event's shape changes) to doing so.
    @Column(name = "payload", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String payload;

    // Enumerated(STRING), not ORDINAL - identical reasoning to
    // ConnectionRequest.status: a string column survives OutboxStatus ever
    // gaining a new constant (e.g. DLQ) without relabeling existing rows.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    // insertable/updatable = false: the DDL's own SYSUTCDATETIME() default
    // is the DB's ground truth for "when did this outbox row actually
    // commit" - same convention as ConnectionRequest.createdAt. OutboxRelay's
    // polling query orders by this column, so it must reflect actual commit
    // order, not app-server clock time that can drift under GC pause or
    // clock skew across replicas.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // Null until OutboxRelay successfully hands this row to Kafka - set
    // together with status flipping to PROCESSED (see markProcessed below).
    @Column(name = "processed_at")
    private Instant processedAt;

    // Tell, Don't Ask (encapsulation) - same convention as
    // ConnectionRequest.transitionTo: OutboxRelay asks this row to mark
    // itself processed rather than reaching in with raw setters and
    // recomputing processedAt by hand at its one call site. No validity
    // check is needed here (unlike transitionTo's VALID_TRANSITIONS map) -
    // PENDING -> PROCESSED is the only transition this entity ever makes.
    public void markProcessed(Instant processedAt) {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = processedAt;
    }
}
