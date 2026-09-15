package com.shareconnectsave.discovery.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

// Event Envelope (Event-Driven Architecture): this record's wire shape is
// owned by contracts/kafka/user.verified.schema.json, not by whatever Java
// field names are convenient here — User Service (.NET) serializes the same
// JSON against its own copy of that schema (see UserVerifiedEvent.cs's
// [JsonPropertyName] attributes), so the two sides only agree if every JSON
// key is pinned explicitly with @JsonProperty rather than left to a naming
// strategy that could silently drift.
//
// @JsonIgnoreProperties(ignoreUnknown = true): the schema is additionalProperties:false
// today, but this consumer should not crash if the producer ever adds a new,
// optional field before this side is updated to read it — Postel's Law
// ("be liberal in what you accept") applied at a service boundary.
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserVerifiedEvent(

        // Idempotency key (kafka-outbox skill) — ProcessedEventsRepository is
        // keyed on this value. Genuinely a UUID from every producer today, so
        // it is typed as one here (unlike userId below).
        @JsonProperty("event_id") UUID eventId,

        // Known follow-up #2 (T025 ticket): the schema declares user_id as
        // "type": "string", "format": "uuid", but User Service's User.Id is
        // actually a .NET `long` (BIGINT IDENTITY) — the producer serializes
        // it to a string for the Kafka payload, but that string is NEVER a
        // real UUID. Deserializing this field as java.util.UUID would throw
        // a JSON mapping exception on every single real message; String is
        // the type that actually matches the data on the wire.
        @JsonProperty("user_id") String userId,

        // "female" | "male" | "unspecified" per the schema's enum — kept as a
        // plain String rather than a Java enum so an unrecognized future
        // value degrades gracefully (this listener does not branch on
        // gender itself, only forwards it into the eligibility cache).
        @JsonProperty("gender") String gender,

        @JsonProperty("verified_at") Instant verifiedAt) {
}
