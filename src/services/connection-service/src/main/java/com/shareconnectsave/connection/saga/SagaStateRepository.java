package com.shareconnectsave.connection.saga;

import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository. Plain JpaRepository — acceptConnection's upsert
// works entirely through save()'s merge-based existence check (see
// SagaState's own comment); no custom query method is needed yet. A future
// saga-timeout job (per saga.md's "detectStuckSagas" example) would add a
// derived query here (e.g. findByCurrentStepAndStatusAndUpdatedAtBefore) —
// intentionally not added now, since no scheduled job exists in this
// service yet to call it.
public interface SagaStateRepository extends JpaRepository<SagaState, Long> {
}
