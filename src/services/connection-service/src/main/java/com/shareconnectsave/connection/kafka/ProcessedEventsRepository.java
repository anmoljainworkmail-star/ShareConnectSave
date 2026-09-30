package com.shareconnectsave.connection.kafka;

import com.shareconnectsave.connection.kafka.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

// Pattern: Repository (GoF/DDD) — same shape as discovery-service's
// ProcessedEventsRepository: TrustScoreUpdatedConsumer only ever asks "has
// this event_id been processed" and "record this event_id", never writes a
// JPQL/SQL query itself for either.
//
// existsById(String) is all the consumer needs — eventId IS this entity's
// @Id, so JpaRepository's own derived existsById/save methods are the exact
// "check, then insert" shape the kafka-outbox skill describes. The one
// addition over discovery-service's version is deleteByProcessedAtBefore,
// needed here because THIS service has no prior consumer (and therefore no
// prior pruning job) to reuse — ProcessedEventsPruner is this service's
// first one, and this derived-delete method is its entire query.
public interface ProcessedEventsRepository extends JpaRepository<ProcessedEvent, String> {

    // Bulk delete, not "load then remove one by one": a derived
    // deleteByProcessedAtBefore(Instant) would still work, but Spring Data
    // would have to load every matching row into the persistence context
    // first just to delete it. @Modifying + @Query issues a single bulk
    // DELETE statement instead — the right shape for a nightly sweep that
    // may touch thousands of rows once this table has been live for a
    // while.
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
    int deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}
