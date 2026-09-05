using System.Text.Json.Serialization;

namespace user_service.Events;

// Event Envelope (Event-Driven Architecture): this DTO's wire shape is owned
// by contracts/kafka/user.verified.schema.json, not by whatever C# property
// names are convenient here — Discovery Service (Java) deserializes the same
// JSON against its own copy of that schema, so the two sides only agree if
// the JSON keys are pinned explicitly. [JsonPropertyName] does that the same
// way ErrorResponse.cs already pins the HTTP error envelope's snake/camel
// casing regardless of ambient JsonSerializerOptions.
//
// EventId is the consumer-side idempotency key (see the schema's own
// "_comment_event_id") — Kafka's at-least-once delivery means Discovery
// Service may see this exact event twice; EventId is what lets it recognize
// and skip the duplicate rather than double-processing it.
public record UserVerifiedEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("user_id")] string UserId,
    [property: JsonPropertyName("gender")] string Gender,
    [property: JsonPropertyName("verified_at")] DateTime VerifiedAt
);
