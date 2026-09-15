package com.shareconnectsave.discovery.kafka;

import com.shareconnectsave.discovery.kafka.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository (GoF/DDD) — same shape as ScanSessionRepository:
// listeners ask "has this event_id been processed" and "record this
// event_id", never write a JPQL/SQL query themselves.
//
// existsById(String) is all either T025 listener needs — eventId IS this
// entity's @Id, so JpaRepository's own derived existsById/save methods are
// the exact "findByEventId, then insert" shape the kafka-outbox skill
// describes; no custom finder method needed on top.
public interface ProcessedEventsRepository extends JpaRepository<ProcessedEvent, String> {
}
