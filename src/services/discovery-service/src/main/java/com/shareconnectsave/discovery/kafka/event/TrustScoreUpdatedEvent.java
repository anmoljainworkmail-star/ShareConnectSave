package com.shareconnectsave.discovery.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/trust.score.updated.schema.json. Rating Service is the
// producer (not yet built — Phase 6) but the schema is the contract this
// record is written against regardless of which side ships first.
//
// Same user_id note as UserVerifiedEvent applies here: the schema marks it
// "format: uuid" but the real value on the wire is User Service's stringified
// long id, so it is deserialized as a plain String, never java.util.UUID.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustScoreUpdatedEvent(

        @JsonProperty("event_id") UUID eventId,

        @JsonProperty("user_id") String userId,

        @JsonProperty("new_score") Double newScore,

        // "trusted" | "standard" | "restricted" — the discretised tier, per
        // the schema's own "_comment_badge_level": consumers key business
        // rules off this tier, not the raw score, so Rating Service can
        // change its scoring thresholds without this service ever changing.
        @JsonProperty("badge_level") String badgeLevel,

        // The suspension signal this listener acts on: request_limit == 0
        // means "remove from scan:eligible_users immediately" (CLAUDE.md:
        // "Very low scores suspend from discovery").
        @JsonProperty("request_limit") Integer requestLimit) {
}
