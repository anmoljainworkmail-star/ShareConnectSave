using System.Text.Json.Serialization;

namespace chat_service.Dtos;

// The Problem: "ReceiveMessage" only reaches connections that joined the
// chat's room group. The moment a user taps back to the radar, their screen
// leaves that group (ChatComponent.ngOnDestroy -> LeaveRoom) -- so a message
// sent to them a second later reaches nobody, and they would never know it
// existed until they happened to reopen the chat.
//
// How this solves it: a SECOND, much smaller push sent to the recipient's
// PERSONAL group (user:{id}), which every live connection of that user is in
// from the moment it connects (ChatHub.OnConnectedAsync) regardless of which
// screen is open. Plain English: ReceiveMessage is a phone call into a
// conference room -- only people sitting in the room hear it. NewMessage is
// a text to their personal phone -- it finds them wherever they are.
//
// Deliberately a thin "something arrived" signal, not the message itself:
// the message body stays on the room channel + the history endpoint (the
// single source of truth). This carries only enough to render a popup, so the
// client can never treat the popup as a second copy of the conversation.
// Wire keys are pinned snake_case like every other payload this service sends.
public record NewMessageNotification(
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("connection_id")] string ConnectionId,
    [property: JsonPropertyName("sender_id")] long SenderId,
    [property: JsonPropertyName("preview")] string Preview);
