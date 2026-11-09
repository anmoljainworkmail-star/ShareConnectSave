package com.shareconnectsave.connection.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/chat.closed.schema.json and produced by Chat Service's
// ChatClosedEvent.cs. This is the CONSUMER's view of that contract, so it
// declares only the fields Connection Service actually uses. user_a_id,
// user_b_id and chat_id are on the wire too, but Connection Service already
// knows both participants from its own connection_requests row, so reading
// them here would only add ways for this side to disagree with its own data.
//
// @JsonIgnoreProperties(ignoreUnknown = true): Postel's Law ("be liberal in what
// you accept") - the producer may add fields later without this consumer
// breaking, and the fields it sends today but we ignore are not an error.
//
// connectionId is a String, not a Long, because that is what is actually on the
// wire: Chat Service keys rooms by connection id and serializes it as a string
// (its schema even declares it format "uuid", which does not match this
// platform's BIGINT ids - a known mismatch noted in ChatClosedEvent.cs). The
// consumer parses it to Long itself, with a guard, same as it does for
// TrustScoreUpdatedEvent.userId.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatClosedEvent(

        // Idempotency key (kafka-outbox skill): processed_events is keyed on it.
        @JsonProperty("event_id") UUID eventId,

        @JsonProperty("connection_id") String connectionId,

        // "met_successfully" | "timeout" | "admin_closed". Logged only: every
        // reason ends the connection the same way, so this consumer does not
        // branch on it. (Rating Service is the subscriber that cares which.)
        @JsonProperty("closed_reason") String closedReason
) {
}
