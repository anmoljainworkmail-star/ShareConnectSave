package com.shareconnectsave.rating.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.rating.TrustScoreRepository;
import com.shareconnectsave.rating.domain.RatingSubmittedEvent;
import com.shareconnectsave.rating.domain.TrustScore;
import com.shareconnectsave.rating.kafka.ProcessedEventRepository;
import com.shareconnectsave.rating.kafka.domain.ProcessedEvent;
import com.shareconnectsave.rating.trustscore.TrustScoreRecalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Self-Consuming Kafka Pattern - RatingServiceImpl.submitRating
// (T042) publishes rating.submitted and returns immediately, deliberately
// never calling TrustScoreCalculator itself; THIS class is what reacts to
// that same event asynchronously. The HTTP request that created the rating
// never blocks on a DB read of rating history or a Kafka publish - exactly
// why this decoupling exists (see this ticket's own "Self-Consuming Kafka
// Pattern" note).
//
// Single Responsibility (SOLID-S): this class's only job is translating "a
// rating.submitted event arrived" into a TrustScoreRecalculationService call
// for the right user - the actual recalculation (fetch ratings, run the
// calculator, upsert, publish) is deliberately NOT inlined here, see that
// service's own class comment for why.
@Component
@RequiredArgsConstructor
@Slf4j
public class RatingSubmittedConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TrustScoreRepository trustScoreRepository;
    private final TrustScoreRecalculationService trustScoreRecalculationService;

    @KafkaListener(topics = "rating.submitted", groupId = "rating-service")
    @Transactional
    public void onRatingSubmitted(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value rating.submitted message, skipping");
            return;
        }

        RatingSubmittedEvent event;
        try {
            event = objectMapper.readValue(rawMessage, RatingSubmittedEvent.class);
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize rating.submitted message, skipping: {}", ex.getMessage());
            return;
        }

        if (event.eventId() == null || event.ratedId() == null) {
            log.error("Received rating.submitted event with a missing required field, skipping: {}", event);
            return;
        }

        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping already-processed rating.submitted event {}", event.eventId());
            return;
        }

        long ratedUserId;
        try {
            ratedUserId = Long.parseLong(event.ratedId());
        } catch (NumberFormatException ex) {
            log.error("Received rating.submitted event {} with a non-numeric rated_id, skipping", event.eventId());
            return;
        }

        // Carry forward the user's last known report count instead of
        // assuming zero - see TrustScore.reportCount's own class comment for
        // why a rating.submitted event alone is never enough information to
        // know whether this user is currently under a report-driven
        // suspension (Database per Service: Report Service, not this one,
        // owns the real count - ReportFiledConsumer is the only writer of a
        // fresh value here).
        long lastKnownReportCount = trustScoreRepository.findByUserId(ratedUserId)
                .map(TrustScore::getReportCount)
                .orElse(0L);

        trustScoreRecalculationService.recalculate(ratedUserId, lastKnownReportCount);

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .build());
    }
}
