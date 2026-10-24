package com.shareconnectsave.admin_service.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/trust.score.updated.schema.json — the SAME schema
// rating-service's own TrustScoreUpdatedEvent (the topic's usual producer)
// and discovery-service's consumer already agree on. admin-service becomes
// this topic's SECOND producer here (reinstate), so this record is
// deliberately field-for-field identical to rating-service's: Discovery
// Service's TrustScoreUpdatedEventListener must not need to know or care
// which service actually published a given message.
//
// badgeLevel is derived from RatingServiceClient.TrustScoreSnapshot.trusted()
// as a simple two-way "trusted"/"standard" mapping in ReviewQueueServiceImpl
// — a deliberate SIMPLIFICATION of rating-service's own three-way
// trusted/standard/restricted derivation (TrustScoreRecalculationServiceImpl),
// since admin-service has no equivalent "restricted" concept of its own to
// derive from and reinstate's whole point is "this account is no longer
// restricted" in the first place. Flagged here, not silently assumed: if a
// future ticket needs admin-service to reproduce Rating Service's exact
// three-way tiering, that logic should move to a shared place both services
// call, not be copy-pasted a second time.
public record TrustScoreUpdatedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("new_score") double newScore,
        @JsonProperty("badge_level") String badgeLevel,
        @JsonProperty("request_limit") int requestLimit
) {
}
