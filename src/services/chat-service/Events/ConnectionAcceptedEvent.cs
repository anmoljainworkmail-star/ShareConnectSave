using System.Text.Json.Serialization;

namespace chat_service.Events;

// Event Envelope (Event-Driven Architecture), same convention as
// user-service's Events/UserVerifiedEvent.cs: this record's wire shape is
// owned by contracts/kafka/connection.accepted.schema.json, not by whatever
// C# property names are convenient here - Connection Service (Java)
// publishes the same JSON shape and Discovery Service/Notification Service
// deserialize it independently on their own side; [JsonPropertyName] is what
// keeps every consumer agreeing on the wire keys regardless of each side's
// own naming convention.
//
// connection_id/requester_id/recipient_id are `long`, matching the schema's
// `format: "int64"` - Connection Service's own id type (a SQL Server
// BIGINT), unlike UserVerifiedEvent.UserId, which is a string on the wire.
// There is no conversion to reconcile here; this record's property types
// already match the schema's declared types exactly.
//
// EventId is the consumer-side idempotency key (same role as
// UserVerifiedEvent.EventId / TrustScoreUpdatedEvent.eventId) - Kafka's
// at-least-once delivery means this consumer may see the exact same event
// twice; EventId is what ConnectionAcceptedHandler's processed_events check
// uses to recognize and skip the duplicate.
public record ConnectionAcceptedEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("connection_id")] long ConnectionId,
    [property: JsonPropertyName("requester_id")] long RequesterId,
    [property: JsonPropertyName("recipient_id")] long RecipientId,
    [property: JsonPropertyName("accepted_at")] DateTime AcceptedAt
);
