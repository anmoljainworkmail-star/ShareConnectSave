package com.shareconnectsave.discovery.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Pattern: Single Responsibility (SOLID-S) — this class owns exactly one
// decision: WHEN to prune (3 AM daily). It has no idea what a user.verified
// or trust.score.updated event looks like, or what UserVerifiedEventListener/
// TrustScoreUpdatedEventListener do with them; that split is entirely theirs.
//
// Pruning (kafka-outbox skill): processed_events grows indefinitely without
// this. Deleting rows older than 7 days is safe because Kafka's own message
// retention is also 7 days — any redelivery of an event this old could never
// actually happen, so there is nothing left to guard against by keeping the
// row. Same shape as connection-service's ProcessedEventsPruner (T033);
// SchedulingConfig already turns on @Scheduled for this service (T024's
// BleTokenCleanupTask), so no new wiring is needed to run this.
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessedEventsPruner {

    private static final int RETENTION_DAYS = 7;

    private final ProcessedEventsRepository processedEventsRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void pruneOldEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = processedEventsRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} processed_events row(s) older than {} days", deleted, RETENTION_DAYS);
        }
    }
}
