package com.shareconnectsave.connection.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/trust.score.updated.schema.json. Rating Service is the
// producer (not yet built — Phase 6) but the schema is the contract this
// record is written against regardless of which side ships first — the
// exact same contract discovery-service's own
// kafka/event/TrustScoreUpdatedEvent.java (T025) already deserializes.
//
// @JsonIgnoreProperties(ignoreUnknown = true): the schema is
// additionalProperties:false today, but this consumer should not crash if
// the producer ever adds a new, optional field before this side is updated
// to read it — Postel's Law ("be liberal in what you accept") applied at a
// service boundary.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustScoreUpdatedEvent(

        // Idempotency key (kafka-outbox skill) — ProcessedEventsRepository is
        // keyed on this value. Genuinely a UUID from every producer today,
        // so it is typed as one here (unlike userId below).
        @JsonProperty("event_id") UUID eventId,

        // Known deviation, same one discovery-service's TrustScoreUpdatedEvent
        // and UserVerifiedEvent already document: the schema declares
        // user_id as "type": "string", "format": "uuid", but every user id
        // on THIS platform is actually a stringified BIGINT (see
        // CreateConnectionDto's own comment on connection_requests.recipient_id
        // being BIGINT, not UNIQUEIDENTIFIER). Deserializing this field as
        // java.util.UUID would throw a JSON mapping exception on every real
        // message; String is what actually matches the wire, and the
        // consumer parses it to Long itself — the type
        // IRequestLimitCache/ConnectionServiceImpl already standardise on
        // for every other user id in this service.
        @JsonProperty("user_id") String userId,

        @JsonProperty("new_score") Double newScore,

        // "trusted" | "standard" | "restricted" per the schema's enum — kept
        // as a plain String rather than a Java enum so an unrecognised
        // future tier degrades gracefully. This consumer never branches on
        // badge_level itself (that is Discovery Service's job); it is only
        // carried through for completeness / future use.
        @JsonProperty("badge_level") String badgeLevel,

        // The one field this consumer actually acts on: RequestLimitCache's
        // per-user throttle value T030's createConnection guard reads.
        @JsonProperty("request_limit") Integer requestLimit) {
}
