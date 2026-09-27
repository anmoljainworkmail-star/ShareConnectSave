package com.shareconnectsave.connection.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/connection.expired.schema.json. Same snake_case
// per-field @JsonProperty convention as ConnectionAcceptedEvent /
// ConnectionRequestedEvent — Connection Service states only the fact "this
// request timed out with no response," and has no idea Notification
// Service exists or what it does with that fact.
//
// Unlike its two siblings, this record has no timestamp field: the schema
// (additionalProperties: false) lists exactly event_id, connection_id,
// requester_id — matching the ticket's payload spec verbatim, since "when"
// the expiry happened is already implicit in expires_at on the
// connection_requests row itself.
//
// No recipientId field either: only the requester's outbound-limit slot
// and inbox entry are affected by an expiry — the recipient never saw a
// terminal state change worth notifying them about.
public record ConnectionExpiredEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("connection_id") Long connectionId,
        @JsonProperty("requester_id") Long requesterId
) {
}
