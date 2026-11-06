using System.Text.Json.Serialization;

namespace chat_service.Dtos;

// SignalR payload pushed to both participants' personal groups when a room
// closes - the counterpart of ChatOpenedNotification. Same snake_case wire
// convention. closed_reason is one of ChatClosedReason's constants so the
// client can pick the rating entry path (met vs timeout) without guessing.
public record ChatClosedNotification(
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("connection_id")] string ConnectionId,
    [property: JsonPropertyName("closed_reason")] string ClosedReason);
