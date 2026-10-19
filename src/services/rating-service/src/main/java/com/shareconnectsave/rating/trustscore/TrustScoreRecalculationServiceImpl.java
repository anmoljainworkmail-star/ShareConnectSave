package com.shareconnectsave.rating.trustscore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.rating.RatingRepository;
import com.shareconnectsave.rating.TrustScoreRepository;
import com.shareconnectsave.rating.domain.BadgeLevel;
import com.shareconnectsave.rating.domain.Rating;
import com.shareconnectsave.rating.domain.RatingSnapshot;
import com.shareconnectsave.rating.domain.RatingTag;
import com.shareconnectsave.rating.domain.RequestLimit;
import com.shareconnectsave.rating.domain.TrustScore;
import com.shareconnectsave.rating.domain.TrustScoreCalculator;
import com.shareconnectsave.rating.domain.TrustScoreResult;
import com.shareconnectsave.rating.kafka.event.TrustScoreUpdatedEvent;
import com.shareconnectsave.rating.outbox.IOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

// Single Responsibility (SOLID-S): this class is the ONE place "a trust
// score needs recalculating" turns into "ratings fetched, pure math run,
// trust_scores upserted, trust.score.updated enqueued" - both
// RatingSubmittedConsumer and ReportFiledConsumer call this instead of each
// re-implementing that whole sequence, so there is exactly one place to get
// it right (see TrustScoreRecalculationService's own comment for the fuller
// "thin consumer -> service layer" reasoning).
//
// Dependency Inversion (SOLID-D): every collaborator here is an interface or
// a plain data mapper - RatingRepository, TrustScoreRepository,
// IOutboxService, and the injected TrustScoreCalculator bean
// (TrustScoreCalculatorConfig) - this class never news up a concrete
// repository or talks to KafkaTemplate directly.
//
// Known, documented contract gap (flagged rather than silently "fixed"):
// contracts/kafka/trust.score.updated.schema.json describes new_score as "in
// range [0, 100]", but TrustScoreCalculator (T043, per SPECS.md's own T043
// spec) produces a 1.0-5.0 value (badge/request-limit thresholds of
// 4.5/3.5/2.5 only make sense on that scale). This class publishes the real
// algorithm output unchanged - it is schema-valid (comfortably within
// minimum 0 / maximum 100) and no shipped consumer (discovery-service,
// connection-service) actually branches on new_score's absolute value, only
// on badge_level/request_limit - so inventing an unspecified x20 rescale here
// would add risk without fixing anything a real caller depends on.
@Service
@RequiredArgsConstructor
public class TrustScoreRecalculationServiceImpl implements TrustScoreRecalculationService {

    private static final int SCORE_SCALE = 2;

    private final RatingRepository ratingRepository;
    private final TrustScoreRepository trustScoreRepository;
    private final IOutboxService outboxService;
    private final TrustScoreCalculator trustScoreCalculator;
    private final ObjectMapper objectMapper;

    // No @Transactional here, deliberately - same reasoning as
    // OutboxServiceImpl.publish's own comment: this method must run inside
    // whichever transaction its caller (each @KafkaListener consumer method,
    // itself @Transactional) already opened. If this method opened its own
    // transaction instead, the trust_scores upsert and the outbox row it
    // writes a few lines later could commit independently of each other -
    // exactly the split-brain the Outbox Pattern exists to prevent.
    @Override
    public void recalculate(Long userId, long reportCount) {
        List<RatingSnapshot> snapshots = ratingRepository.findByRatedId(userId).stream()
                .map(this::toSnapshot)
                .toList();

        TrustScoreResult result = trustScoreCalculator.calculate(snapshots, reportCount);

        TrustScore trustScore = trustScoreRepository.findByUserId(userId)
                .orElseGet(() -> TrustScore.builder().userId(userId).build());
        trustScore.applyRecalculation(
                BigDecimal.valueOf(result.score()).setScale(SCORE_SCALE, RoundingMode.HALF_UP),
                result.badgeLevel(),
                result.requestLimit(),
                reportCount);

        // Unique Index Race on Brand-New User: two concurrent consumers both
        // creating a new TrustScore for the same brand-new user can both pass
        // findByUserId().isEmpty() and both attempt INSERT. The first
        // succeeds; the second hits idx_trust_scores_user_id and throws
        // DataIntegrityViolationException. Rather than catching and retrying
        // in application code (which leaves the transaction marked rollback-only,
        // silently discarding the outbox row and processed_events idempotency
        // marker), we let the exception propagate uncaught. This rolls back the
        // whole Kafka-listener transaction cleanly, the Kafka offset is never
        // committed, and Kafka's at-least-once redelivery reprocesses the same
        // message later — by then the other transaction has already committed its
        // row, so the retry becomes a normal update with no violation. This
        // recovery relies on the same processed_events idempotency check already
        // in the calling @KafkaListener method.
        trustScoreRepository.save(trustScore);

        // Outbox Pattern: this write and the trust_scores save above commit
        // together inside the caller's @Transactional boundary - a crash
        // between "save the new score" and "publish trust.score.updated" can
        // never leave a recalculated score nobody downstream ever learns
        // about. Partition key = userId (the aggregate this event describes),
        // matching this platform's "partition by the aggregate" convention -
        // also what the ticket's own note about ordering per user_id across
        // rating.submitted and report.filed arrivals relies on.
        String eventId = UUID.randomUUID().toString();
        TrustScoreUpdatedEvent event = new TrustScoreUpdatedEvent(
                eventId,
                userId.toString(),
                result.score(),
                toWireBadgeLevel(result),
                result.requestLimit().wireValue());
        outboxService.publish("trust.score.updated", userId.toString(), eventId, event);
    }

    private RatingSnapshot toSnapshot(Rating rating) {
        return new RatingSnapshot(rating.getCreatedAt(), deserializeTags(rating.getTags()));
    }

    private List<RatingTag> deserializeTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<RatingTag>>() {
            });
        } catch (JsonProcessingException e) {
            // Mirror of RatingServiceImpl.serializeTags's own symmetric throw:
            // this JSON was written by THIS service's own
            // RatingServiceImpl.serializeTags moments before it was
            // persisted, so a failure to read it back is a genuine data/
            // programmer error, not a transient condition worth swallowing.
            throw new IllegalStateException("Failed to deserialize rating tags JSON: " + tagsJson, e);
        }
    }

    // Boundary mapping (same spirit as RequestLimit.wireValue(), T043): the
    // trust.score.updated schema's badge_level enum has THREE tiers
    // (trusted/standard/restricted), but T043's BadgeLevel domain enum only
    // has two (NONE/TRUSTED) - SPECS.md's T043 spec never defined a third
    // internal badge tier because "restricted" is really a request-limit
    // concept (suspension), not a rating-quality one. Rather than widening
    // BadgeLevel itself (which would force every other BadgeLevel reader,
    // including TrustScore's own @Enumerated column, to learn about a
    // concept it has no other use for), this derivation lives here, at the
    // one place both halves of TrustScoreResult are available together:
    // SUSPENDED request limit wins first (a report-driven or score-driven
    // suspension is "restricted" regardless of which caused it), then
    // BadgeLevel.TRUSTED, then everything else falls to "standard".
    private String toWireBadgeLevel(TrustScoreResult result) {
        if (result.requestLimit() == RequestLimit.SUSPENDED) {
            return "restricted";
        }
        return result.badgeLevel() == BadgeLevel.TRUSTED ? "trusted" : "standard";
    }
}
