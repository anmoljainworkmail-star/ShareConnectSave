using System.Text.Json.Serialization;

namespace notification_service.Events;

// Event Envelope — see ConnectionRequestedEvent's comment for the full
// "why a second, separate copy of this shape" reasoning. This record mirrors
// chat-service's Events/ConnectionAcceptedEvent.cs field-for-field (same
// contracts/kafka/connection.accepted.schema.json), but lives in
// notification_service's own namespace: chat-service's record is for
// opening a chat room, this one is for deciding who gets a
// RequestAccepted push — two independent subscribers to the same topic
// (Observer, GoF), neither aware the other's copy of this type exists.
public record ConnectionAcceptedEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("connection_id")] long ConnectionId,
    [property: JsonPropertyName("requester_id")] long RequesterId,
    [property: JsonPropertyName("recipient_id")] long RecipientId,
    [property: JsonPropertyName("accepted_at")] DateTime AcceptedAt
);
