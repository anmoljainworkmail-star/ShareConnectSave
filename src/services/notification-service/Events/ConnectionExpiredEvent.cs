using System.Text.Json.Serialization;

namespace notification_service.Events;

// Event Envelope — see ConnectionRequestedEvent's comment for the shared
// "why a per-service copy" reasoning. Note this schema has NO
// recipient_id — contracts/kafka/connection.expired.schema.json's own
// _comment_saga explains why: expiry is the compensating branch of the
// connection lifecycle saga (request -> accept OR expire), and only the
// requester who is left waiting ever needs to know their own request timed
// out. A recipient who never responded has nothing to be notified about.
public record ConnectionExpiredEvent(
    [property: JsonPropertyName("event_id")] Guid EventId,
    [property: JsonPropertyName("connection_id")] long ConnectionId,
    [property: JsonPropertyName("requester_id")] long RequesterId
);
