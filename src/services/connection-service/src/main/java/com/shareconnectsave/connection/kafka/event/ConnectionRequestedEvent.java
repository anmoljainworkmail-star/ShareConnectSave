package com.shareconnectsave.connection.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/connection.requested.schema.json. Same snake_case
// per-field @JsonProperty convention as ConnectionAcceptedEvent — Connection
// Service has no idea Notification Service exists or how it reacts; this
// event only states the fact "a request was sent," nothing more.
public record ConnectionRequestedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("connection_id") Long connectionId,
        @JsonProperty("requester_id") Long requesterId,
        @JsonProperty("recipient_id") Long recipientId,
        @JsonProperty("requested_at") Instant requestedAt
) {
}
