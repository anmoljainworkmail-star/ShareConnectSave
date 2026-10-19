package com.shareconnectsave.rating.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.rating.kafka.ProcessedEventRepository;
import com.shareconnectsave.rating.kafka.domain.ProcessedEvent;
import com.shareconnectsave.rating.kafka.event.ReportFiledEvent;
import com.shareconnectsave.rating.trustscore.TrustScoreRecalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Observer (via events) - Report Service (T054) publishes
// report.filed once; Admin Service (escalating the review queue) and this
// consumer (forcing a trust score recalculation) both react independently to
// the same fact.
//
// Open/Closed-adjacent (No Hardcoded Config, Architecture Rule #3):
// reportSuspensionThreshold is read from configuration
// (rating.report.suspension.threshold, application.yml), never a literal `3`
// in this class - changing the policy is a config change, not a redeploy.
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportFiledConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TrustScoreRecalculationService trustScoreRecalculationService;

    // Field injection for this one primitive config value, not constructor
    // injection: @RequiredArgsConstructor only generates parameters for
    // `final` fields, and this value is intentionally non-final so Spring's
    // ordinary @Value field injection can populate it after construction -
    // mixing the two injection styles on one class is a normal, supported
    // Spring pattern, not a shortcut around DI.
    @Value("${rating.report.suspension.threshold}")
    private long reportSuspensionThreshold;

    @KafkaListener(topics = "report.filed", groupId = "rating-service")
    @Transactional
    public void onReportFiled(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value report.filed message, skipping");
            return;
        }

        ReportFiledEvent event;
        try {
            event = objectMapper.readValue(rawMessage, ReportFiledEvent.class);
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize report.filed message, skipping: {}", ex.getMessage());
            return;
        }

        if (event.eventId() == null || event.reportedId() == null || event.reportCountForTarget() == null) {
            log.error("Received report.filed event with a missing required field, skipping: {}", event);
            return;
        }

        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping already-processed report.filed event {}", event.eventId());
            return;
        }

        long reportedUserId;
        try {
            reportedUserId = Long.parseLong(event.reportedId());
        } catch (NumberFormatException ex) {
            log.error("Received report.filed event {} with a non-numeric reported_id, skipping", event.eventId());
            return;
        }

        // Guard clause, not a nested if: below-threshold is a legitimate,
        // expected outcome (most reports never reach the threshold) - it is
        // still recorded as processed below so a redelivery of this exact
        // under-threshold event does not redo the check forever, but it is
        // not treated as an error or logged as one.
        if (event.reportCountForTarget() >= reportSuspensionThreshold) {
            trustScoreRecalculationService.recalculate(reportedUserId, event.reportCountForTarget());
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .build());
    }
}
