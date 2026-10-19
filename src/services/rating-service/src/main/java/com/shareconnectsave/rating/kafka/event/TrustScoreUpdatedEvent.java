package com.shareconnectsave.rating.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/trust.score.updated.schema.json. Rating Service is the
// producer - TrustScoreRecalculationServiceImpl builds this record and hands
// it to IOutboxService.publish, which serializes it via
// ObjectMapper.writeValueAsString(this), same flow RatingServiceImpl already
// uses for RatingSubmittedEvent.
//
// userId is a stringified Long, not java.util.UUID, matching every other id
// on this platform's wire contracts (see RatingSubmittedEvent's own comment
// on this exact convention).
//
// requestLimit is a plain int, NEVER the RequestLimit enum's name - always
// built from TrustScoreResult.requestLimit().wireValue() (T043's boundary
// mapping), never passed the enum directly. discovery-service (T025) and
// connection-service (T030/T033) already ship against the integer contract;
// sending "THROTTLED" instead of 3 would throw a JSON mapping exception on
// both.
//
// badgeLevel here is a plain String ("trusted"/"standard"/"restricted" per
// the schema's enum), not T043's two-value BadgeLevel enum (NONE/TRUSTED) -
// see TrustScoreRecalculationServiceImpl.toWireBadgeLevel for the documented
// three-way derivation this boundary requires.
//
// newScore is published exactly as TrustScoreCalculator computes it (a 1.0-5.0
// value per SPECS.md's T043 algorithm), even though this schema's own
// description says "in range [0, 100]" - see
// TrustScoreRecalculationServiceImpl's class comment for why that mismatch is
// flagged, not silently "fixed" with an unspecified rescale: no shipped
// consumer (discovery-service, connection-service) branches on new_score's
// absolute value today, only on badge_level/request_limit.
public record TrustScoreUpdatedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("new_score") double newScore,
        @JsonProperty("badge_level") String badgeLevel,
        @JsonProperty("request_limit") int requestLimit
) {
}
