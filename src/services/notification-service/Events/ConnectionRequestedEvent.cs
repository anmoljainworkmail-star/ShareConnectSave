using System.Text.Json.Serialization;

namespace notification_service.Events;

// Event Envelope (Event-Driven Architecture), same convention as chat-
// service's ConnectionAcceptedEvent — this record's wire shape is owned by
// contracts/kafka/connection.requested.schema.json, not by whatever C#
// property names are convenient here. Connection Service (Java) publishes
// this JSON shape once; Notification Service deserializes it independently
// on its own side, with its own copy of this record in its own namespace —
// there is no shared library between these two .NET projects, so the two
// copies staying byte-for-byte aligned with the schema file is what keeps
// them agreeing, not a compile-time reference to one shared type.
//
// EventId is the consumer-side idempotency key (ConnectionRequestedHandler's
// processed_events check uses it to recognize and skip a Kafka at-least-once
// redelivery of the exact same event).
public record ConnectionRequestedEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("connection_id")] long ConnectionId,
    [property: JsonPropertyName("requester_id")] long RequesterId,
    [property: JsonPropertyName("recipient_id")] long RecipientId,
    [property: JsonPropertyName("requested_at")] DateTime RequestedAt
);
