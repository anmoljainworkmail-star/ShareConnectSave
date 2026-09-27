package com.shareconnectsave.connection.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the outbox table from V002__outbox_and_saga_state.sql.
//
// Outbox Pattern: this is the FIRST outbox table in the codebase (Connection
// Service is the first ticket to need it) — every other Kafka-touching
// service so far (discovery-service's kafka/domain/ProcessedEvent) only
// ever consumed events, never produced one. A row here is written inside
// the SAME @Transactional method that flips connection_requests.status to
// ACCEPTED (see ConnectionServiceImpl.acceptConnection), so either both the
// status change and this row commit together, or neither does — a crash
// between "update the DB" and "publish to Kafka" can never leave a
// half-finished saga step, because nothing has actually tried to reach
// Kafka yet at that point. The row sits here as durable, committed proof of
// intent; only a separate, later process (a scheduled relay — explicitly
// OUT OF SCOPE for this ticket, see OutboxServiceImpl's own comment) reads
// PENDING rows and hands them to KafkaTemplate.
//
// Lombok: @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor, not
// @Data — same reasoning as every other @Entity in this codebase (see
// ConnectionRequest's class comment): @Data's generated equals()/hashCode()
// over every field breaks JPA's identity and lazy-loading assumptions.
@Entity
@Table(name = "outbox")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic", nullable = false)
    private String topic;

    // JSON string, not a JPA-mapped object graph: the relay (future ticket)
    // only ever needs to hand this value, byte-for-byte, to
    // KafkaTemplate.send() — it never queries into the payload's fields, so
    // there is nothing to gain from modelling its structure in this table
    // and a real cost (a schema migration every time an event's shape
    // changes) to doing so.
    @Column(name = "payload", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String payload;

    // Enumerated(STRING), not ORDINAL — identical reasoning to
    // ConnectionRequest.status: a string column survives OutboxStatus ever
    // gaining a new constant (e.g. DLQ) without relabeling existing rows.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    // insertable/updatable = false: the DDL's own SYSUTCDATETIME() default
    // is the DB's ground truth for "when did this outbox row actually
    // commit" — same convention as ConnectionRequest.createdAt. The future
    // relay's polling query orders by this column, so it must reflect
    // actual commit order, not app-server clock time that can drift under
    // GC pause or clock skew across replicas.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
