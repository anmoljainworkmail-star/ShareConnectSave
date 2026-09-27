package com.shareconnectsave.connection.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository — plain JpaRepository, no custom query methods yet.
// The future relay (T032, out of scope here) is what will need
// findByStatusOrderByCreatedAtAsc(...); adding that method now, unused,
// would be speculative — this ticket's job is only the write side
// (OutboxServiceImpl.publish saving a PENDING row), so the repository
// exposes exactly what today's one caller needs (Interface Segregation
// in spirit, even though JpaRepository itself is a wide interface by
// framework design).
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}
