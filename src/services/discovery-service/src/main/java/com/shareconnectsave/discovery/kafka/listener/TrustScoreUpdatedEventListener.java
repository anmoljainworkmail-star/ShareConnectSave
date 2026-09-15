package com.shareconnectsave.discovery.kafka.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.discovery.cache.DiscoveryEligibilityCacheService;
import com.shareconnectsave.discovery.kafka.ProcessedEventsRepository;
import com.shareconnectsave.discovery.kafka.domain.ProcessedEvent;
import com.shareconnectsave.discovery.kafka.event.TrustScoreUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Observer (via Kafka) — Rating Service (Phase 6, not yet built)
// publishes a recalculated trust score once; this listener is the one
// subscriber that keeps Discovery's own read-side projection of that score
// in sync. Neither service knows the other's internals: Rating Service has
// no idea Discovery Service exists, only that "trust.score.updated" is a
// fact worth publishing.
@Component
@RequiredArgsConstructor
@Slf4j
public class TrustScoreUpdatedEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventsRepository processedEventsRepository;
    private final DiscoveryEligibilityCacheService eligibilityCacheService;

    // Same consumer group as UserVerifiedEventListener ("discovery-service")
    // — Kafka consumer groups are scoped per TOPIC-partition assignment, not
    // shared state between listeners, so two @KafkaListener methods in this
    // service can safely use the identical groupId without one starving the
    // other's partition assignment.
    @KafkaListener(topics = "trust.score.updated", groupId = "discovery-service")
    @Transactional
    public void onTrustScoreUpdated(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value trust.score.updated message, skipping");
            return;
        }

        TrustScoreUpdatedEvent event;
        try {
            event = objectMapper.readValue(rawMessage, TrustScoreUpdatedEvent.class);
        } catch (JsonProcessingException ex) {
            // Same fail-safe reasoning as UserVerifiedEventListener: log and
            // skip, never throw — a thrown exception here would block the
            // offset commit and Kafka would redeliver this exact unparseable
            // message forever. Only log the exception message, not the raw
            // payload — the payload may contain sensitive fields like gender
            // that should never be exposed in application logs.
            log.error("Failed to deserialize trust.score.updated message, skipping: {}", ex.getMessage());
            return;
        }

        // Guard clause: reject messages with null event_id before attempting
        // to use it. A null event_id would cause NullPointerException below
        // outside the try/catch, stalling the partition with an unrecoverable
        // poison pill. Log and skip instead.
        if (event.eventId() == null) {
            log.error("Received trust.score.updated event with null event_id, skipping");
            return;
        }

        // Guard clause: reject messages where any of newScore, badgeLevel, or
        // requestLimit is null. The cacheTrustMetadata call below uses Map.of()
        // with these values, which throws NullPointerException if any are null,
        // crashing the consumer. For a suspension (requestLimit == 0), a null
        // score or badge would silently skip the removeEligible call, breaking
        // the acceptance criterion. Always validate before caching.
        if (event.newScore() == null || event.badgeLevel() == null || event.requestLimit() == null) {
            log.error("Received trust.score.updated event {} with a missing required field, skipping", event.eventId());
            return;
        }

        // Idempotency check — identical reasoning to UserVerifiedEventListener.
        if (processedEventsRepository.existsById(event.eventId().toString())) {
            log.info("Skipping already-processed trust.score.updated event {}", event.eventId());
            return;
        }

        // Cache-Aside write: refresh the cached trust metadata unconditionally
        // first — every trust update (not only a suspension) needs the
        // latest score/badge available to whatever future ticket wires a
        // read of this Hash into the discovery query or request-throttling
        // path.
        eligibilityCacheService.cacheTrustMetadata(
                event.userId(), event.newScore(), event.badgeLevel(), event.requestLimit());

        // Guard clause (CLAUDE.md: early return over nested ifs) — the
        // suspension signal is the ONE branch that also mutates the
        // eligibility gate itself; every other trust update only refreshes
        // the metadata cache above and returns from here.
        if (event.requestLimit() != null && event.requestLimit() == 0) {
            eligibilityCacheService.removeEligible(event.userId());
        }

        processedEventsRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId().toString())
                .build());
    }
}
