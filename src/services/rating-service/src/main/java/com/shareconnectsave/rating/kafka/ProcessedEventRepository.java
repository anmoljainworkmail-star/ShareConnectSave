package com.shareconnectsave.rating.kafka;

import com.shareconnectsave.rating.kafka.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository - plain JpaRepository, no "I" prefix (RatingRepository's
// class comment has the full project-wide convention this follows).
//
// existsById(String) + save(...) is the entire idempotency check-then-insert
// shape all three of this ticket's consumers use - eventId IS this entity's
// @Id, so no custom query is ever needed here, same as connection-service's
// ProcessedEventsRepository.
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
