package com.shareconnectsave.connection.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Pattern: Repository - plain JpaRepository, per this project's convention
// of NOT prefixing repository interfaces with "I" (connection-service was
// already renamed away from IXxxRepository; see the java-repository-naming
// memory).
//
// findByStatusOrderByCreatedAtAsc backs OutboxRelay's poll query (T032):
// oldest-PENDING-first ordering is what makes the relay a FIFO-ish queue per
// partition key rather than an unordered bag, and returning a bounded List
// (via the Pageable argument) instead of a full Page avoids Spring Data
// running a second COUNT(*) query the relay has no use for.
public interface OutboxRepository extends JpaRepository<OutboxEvent, java.util.UUID> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);
}
