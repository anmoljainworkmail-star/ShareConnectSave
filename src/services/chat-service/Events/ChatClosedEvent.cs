using System.Text.Json.Serialization;

namespace chat_service.Events;

// Event Envelope (Event-Driven Architecture), same convention as
// user-service's Events/UserVerifiedEvent.cs: this record's wire shape is
// owned by contracts/kafka/chat.closed.schema.json, not by whatever C#
// property names are convenient here. That schema declares
// `additionalProperties: false` with exactly six required fields - this
// record intentionally has ONLY those six properties, no more (the T037
// ticket addendum's own illustrative field list mentions a closed-at
// timestamp, but the checked-in schema does not include one, so this record
// follows the schema, not the ticket prose).
//
// UserAId/UserBId are serialized as strings even though ChatRoomEntity
// stores them as `long` - the schema declares both as `format: "uuid"`
// strings, the same string-ids-over-the-wire choice UserVerifiedEvent.cs
// already makes for its own UserId. (Note for whoever picks up T039: the
// schema's `format: "uuid"` on chat_id/user_a_id/user_b_id does not actually
// match this service's real id shapes - chat_id is a Mongo ObjectId, not a
// UUID, and user ids are longs cast to string, not UUIDs either. That
// mismatch pre-dates this ticket and is out of scope to fix here; flagging
// it rather than silently working around it.)
//
// EventId is the consumer-side idempotency key (same role as
// UserVerifiedEvent.EventId) - Kafka's at-least-once delivery means Rating
// Service/Notification Service may see this event twice once T039 actually
// publishes it; EventId is what lets a consumer recognize and skip the
// duplicate.
public record ChatClosedEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("connection_id")] string ConnectionId,
    [property: JsonPropertyName("user_a_id")] string UserAId,
    [property: JsonPropertyName("user_b_id")] string UserBId,
    [property: JsonPropertyName("closed_reason")] string ClosedReason
);
