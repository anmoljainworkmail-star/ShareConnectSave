namespace chat_service.Dtos;

using System.Text.Json.Serialization;

// Response for GET /chats/by-connection/{connectionId}. Same two wire keys as
// ChatOpenedNotification on purpose: this endpoint is the "pull" twin of that
// "push". The push only reaches a client that is connected at the instant the
// room opens; a client that reloads the page afterwards (or connects late)
// missed it and would never learn its chat id. This is the catch-up path.
//
// status + closed_reason: the pull path is also the client's only way to
// notice a close it missed. ChatClosed is a push, and pushes are not replayed
// to an offline client (at-most-once delivery). Carrying the room's CURRENT
// state in the answer lets the client check the truth on every reconnect
// instead of trusting that the push arrived (state transfer, not event replay).
// status is the wire string "OPEN" / "CLOSING" / "CLOSED"; closed_reason is
// null until the room is CLOSED.
//
// Deliberately carries NO participant ids, gender, or message data - the
// caller only needs the room's address and state, nothing about the other traveler.
public record ChatRoomLookupResponse(
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("connection_id")] string ConnectionId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("closed_reason")] string? ClosedReason);
