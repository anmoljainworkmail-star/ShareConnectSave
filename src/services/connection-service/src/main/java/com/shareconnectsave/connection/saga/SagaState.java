package com.shareconnectsave.connection.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the saga_state table from V002__outbox_and_saga_state.sql,
// matching the saga.md skill's SQL shape exactly except sagaId is BIGINT
// (this project's connection ids), not UNIQUEIDENTIFIER.
//
// Saga (Choreography): this row is Connection Service's own paper trail for
// where ConnectionLifecycleSaga currently stands, from ITS point of view
// only — there is no central coordinator that owns the "real" saga state
// across all participants. Chat Service, Rating Service, and any future
// participant each keep their own saga_state table (or none, per
// UserOnboardingSaga's simpler append-only shape) and learn what happened
// elsewhere purely from Kafka events, never by reading this table directly
// (Database per Service).
//
// sagaId has NO @GeneratedValue: unlike ConnectionRequest.id (an
// IDENTITY column SQL Server assigns), this id IS the connection's own id,
// assigned by the caller (ConnectionServiceImpl.acceptConnection passes
// connectionRequest.getId()). Because Hibernate sees a manually-assigned,
// non-null @Id with no generator, JpaRepository.save() resolves to an
// entityManager.merge() rather than persist() — Hibernate checks whether a
// row with this id already exists and issues an UPDATE if so, an INSERT if
// not. That merge-based existence check is what makes a plain
// sagaStateRepository.save(...) behave as the ticket's required "upsert"
// with no hand-written "SELECT ... then INSERT-or-UPDATE" branch anywhere
// in this codebase.
//
// startedAt/updatedAt are set explicitly by ConnectionServiceImpl (not
// insertable/updatable = false like ConnectionRequest's DB-default
// columns): this entity's shape is "caller supplies everything, including
// the id" throughout, so its timestamps follow that same convention rather
// than mixing in a DB-default for just these two columns.
@Entity
@Table(name = "saga_state")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaState {

    @Id
    @Column(name = "saga_id")
    private Long sagaId;

    @Column(name = "saga_type", nullable = false)
    private String sagaType;

    // "ACCEPTED" as of this ticket's only writer (acceptConnection) — later
    // steps (CHAT_OPEN, CHAT_CLOSED, ...) are written by OTHER services'
    // own saga_state tables, per the class comment above, not appended here.
    @Column(name = "current_step", nullable = false)
    private String currentStep;

    // "in_progress" | "completed" | "compensating" | "failed" — a plain
    // String, not a Java enum, deliberately: this column's value set is
    // shared, informally, across every participating service's saga_state
    // table (per saga.md), so no single service's enum should be able to
    // "own" or constrain it.
    @Column(name = "status", nullable = false)
    private String status;

    // Nullable JSON snapshot of relevant ids for this step — unused by this
    // ticket's write (acceptConnection passes null), present because the
    // saga.md skill's shape includes it and a later saga step/ticket may
    // want to record context here without a schema change.
    @Column(name = "context", columnDefinition = "NVARCHAR(MAX)")
    private String context;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
