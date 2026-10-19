package com.shareconnectsave.rating.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/chat.closed.schema.json. Chat Service is the producer
// (chat-service/Events/ChatClosedEvent.cs) - this record is Rating Service's
// OWN copy of the same contract, not a shared cross-language class, because
// each service's deserialization target is a local concern (same reasoning
// connection-service's own TrustScoreUpdatedEvent.java documents for a
// Rating-Service-produced event it consumes).
//
// connection_id/user_a_id/user_b_id are declared `format: "uuid"` strings in
// the schema, but every id on this platform is actually a stringified
// BIGINT (see RequestLimit's class comment and chat-service's own
// ChatClosedEvent.cs comment flagging this exact, pre-existing mismatch) -
// kept as String here and parsed to long by ChatClosedConsumer, never typed
// as java.util.UUID.
//
// @JsonIgnoreProperties(ignoreUnknown = true): Postel's Law at a service
// boundary - this consumer should not break if Chat Service ever adds an
// optional field (e.g. a closed-at timestamp) before this side reads it.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatClosedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("connection_id") String connectionId,
        @JsonProperty("user_a_id") String userAId,
        @JsonProperty("user_b_id") String userBId,
        @JsonProperty("closed_reason") String closedReason
) {
}
