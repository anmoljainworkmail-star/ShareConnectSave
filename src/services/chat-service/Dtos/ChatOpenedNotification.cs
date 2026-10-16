using System.Text.Json.Serialization;

namespace chat_service.Dtos;

// SignalR payload (not a Kafka event - see ConnectionAcceptedHandler's
// comment for why this ticket does not publish one). Same convention as
// MessageDto/MarkMetSuccessfullyResponse: [JsonPropertyName] pins the wire
// keys explicitly so the Angular client sees the same snake_case shape on
// every broadcast this service sends, regardless of which hub method or
// background handler produced it.
public record ChatOpenedNotification(
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("connection_id")] string ConnectionId);
