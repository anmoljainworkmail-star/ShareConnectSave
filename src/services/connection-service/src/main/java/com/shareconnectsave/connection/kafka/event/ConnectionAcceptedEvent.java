package com.shareconnectsave.connection.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/connection.accepted.schema.json. Snake_case field names
// via per-field @JsonProperty — same convention as discovery-service's
// TrustScoreUpdatedEvent — because this project has no project-wide Jackson
// naming strategy; ObjectMapper.writeValueAsString(this) in
// OutboxServiceImpl therefore already produces the exact wire shape Chat
// Service and Notification Service expect, with zero extra configuration.
//
// eventId is a freshly-minted UUID string, generated once per publish call
// (ConnectionServiceImpl.acceptConnection) — this is Kafka's OWN dedup key
// for a future consumer's Idempotency check (processed_events table,
// per the kafka-outbox skill), completely unrelated to connectionId /
// requesterId / recipientId, which stay Long per this ticket's platform-wide
// BIGINT convention.
public record ConnectionAcceptedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("connection_id") Long connectionId,
        @JsonProperty("requester_id") Long requesterId,
        @JsonProperty("recipient_id") Long recipientId,
        @JsonProperty("accepted_at") Instant acceptedAt
) {
}
