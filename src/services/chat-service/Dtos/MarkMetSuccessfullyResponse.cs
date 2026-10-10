namespace chat_service.Dtos;

using System.Text.Json.Serialization;

// Response for POST /chats/:id/met. `both_users_met` is the signal a client
// can use to show "waiting on the other traveler" vs. "chat is now closing"
// - it deliberately does NOT include a `status` field, because the actual
// OPEN/CLOSING/CLOSED state machine does not exist in this codebase yet
// (T037's job); this response only reflects the two per-user confirmation
// facts ChatService.MarkMetSuccessfullyAsync actually has.
public record MarkMetSuccessfullyResponse(
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("both_users_met")] bool BothUsersMet);
