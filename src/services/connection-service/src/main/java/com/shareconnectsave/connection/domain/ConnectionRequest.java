package com.shareconnectsave.connection.domain;

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

// Maps onto the connection_requests table from
// V001__connection_service_initial_schema.sql. This entity is also where the
// ConnectionLifecycleSaga's steps begin (saga ID = this row's id): a
// PENDING row here is step 1 of "accept -> open chat -> met -> rate", and
// every later saga participant (Chat, Rating, Discovery) only ever learns
// about this row's state through a Kafka event this service publishes about
// it — never by querying this table directly (Database per Service).
//
// Lombok: @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor, not
// @Data — @Data would generate equals()/hashCode() over every field,
// including the lazy/mutable ones, which breaks JPA's identity and
// lazy-loading assumptions the moment two rows are compared.
@Entity
@Table(name = "connection_requests")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    // Enumerated(STRING), not ORDINAL: a string column survives a future
    // reordering or insertion of enum constants without silently relabeling
    // existing rows (ORDINAL stores the constant's declaration index, which
    // shifts if anyone ever reorders ConnectionStatus). The column itself
    // stays a plain NVARCHAR(20) in the DB — see ConnectionStatus's own
    // comment for why that's an Open/Closed choice, not just a JPA detail.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConnectionStatus status;

    // insertable/updatable = false: the DDL's own SYSUTCDATETIME() default is
    // the DB's ground truth for "when was this row actually committed", not
    // the app server's clock — same reasoning as discovery-service's
    // BleSeed.createdAt. This value is set once, at INSERT time, and never
    // changes again.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // insertable = false only (not updatable = false): the DB fills this in
    // at INSERT time via the same SYSUTCDATETIME() default as createdAt, but
    // unlike createdAt this column DOES change later — the TTL sweep and
    // accept/decline state transitions (future tickets) update it on every
    // status change. No DB trigger recomputes it on UPDATE, so that future
    // application code is responsible for setting it explicitly before
    // saving.
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;
}
