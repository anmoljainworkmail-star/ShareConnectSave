namespace chat_service.Services.Interfaces;

using chat_service.Dtos;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D): this is
// the one interface both ChatHub (real-time) and ChatController (REST) ask
// the DI container for - neither one talks to IChatRoomRepository /
// IMessageRepository directly. Every business rule this ticket introduces
// ("cap history at 50", "only a participant may read/send/confirm",
// "a closed chat refuses new messages/confirmations", "both sides must
// confirm before a chat is eligible to close") lives here, in exactly one
// place, instead of being duplicated - or worse, drifting - between the hub
// and the controller. Same shape as user-service's IUserProfileService
// giving UserProfileController a Result Object per method instead of a
// thrown exception, for the same reason: "not a participant" or "chat
// already closed" are ROUTINE control flow a caller must branch on, not
// exceptional conditions.
public interface IChatService
{
    Task<SendMessageOutcome> SendMessageAsync(string chatId, long senderId, string content);

    Task<GetMessagesOutcome> GetRecentMessagesAsync(string chatId, long requestingUserId, int? requestedLimit);

    Task<MarkMetOutcome> MarkMetSuccessfullyAsync(string chatId, long userId);

    // T036 fix: ChatHub.JoinRoom needs the SAME "is this caller actually a
    // participant of this room" rule SendMessageAsync/GetRecentMessagesAsync/
    // MarkMetSuccessfullyAsync already enforce, but a group-join has no
    // richer Result Object to return - there is nothing for a caller to do
    // with "not found" vs "not a participant" here, it is a single yes/no
    // gate before Groups.AddToGroupAsync. A malformed/unknown chatId and a
    // real room the caller isn't part of collapse to the same false, same as
    // every other method's chatId guard below.
    Task<bool> IsParticipantAsync(string chatId, long userId);

    // T068 follow-up: the Angular route is /chat/:connectionId (the id the
    // connection flow already has) but every chat operation keys on the Mongo
    // chat id. This is the lookup that bridges the two, so a client that
    // missed the one-time ChatOpened push (page reload, late connect) can
    // still find its room. A non-participant gets the same ChatRoomNotFound as
    // a missing room - see GetChatByConnectionResult's comment.
    Task<GetChatByConnectionOutcome> GetChatByConnectionAsync(string connectionId, long requestingUserId);
}

// Existence oracle (security): if "exists but not yours" had its own result,
// a caller could walk connection ids and learn which ones have rooms. Plain
// English: a hotel that answers "room is occupied" to strangers and "no such
// room" to nobody has told them which doors are used. So "not yours" and
// "does not exist" share one outcome, and the controller maps both to the
// same 404.
public enum GetChatByConnectionResult
{
    Success,
    ChatRoomNotFound,
}

public record GetChatByConnectionOutcome(GetChatByConnectionResult Result, ChatRoomLookupResponse? Room);

// Result Object (same convention as user-service's ProfileUpdateResult):
// ChatHub.SendMessage and ChatController share this exact outcome shape -
// ChatHub translates a non-Success result into a HubException, ChatController
// translates it into an ErrorResponse + HTTP status code. Each caller adapts
// the SAME decision to its own transport; neither re-derives it.
public enum SendMessageResult
{
    Success,
    InvalidRequest,
    ChatRoomNotFound,
    NotAParticipant,
    ChatRoomClosed,
}

public record SendMessageOutcome(SendMessageResult Result, MessageDto? Message, string? ErrorMessage);

public enum GetMessagesResult
{
    Success,
    ChatRoomNotFound,
    NotAParticipant,
}

public record GetMessagesOutcome(GetMessagesResult Result, IReadOnlyList<MessageDto>? Messages);

public enum MarkMetResult
{
    Success,
    ChatRoomNotFound,
    NotAParticipant,
    ChatRoomClosed,
}

// BothUsersMet is only meaningful when Result is Success - see
// ChatService.MarkMetSuccessfullyAsync's comment for why this ticket
// deliberately stops at "both sides have now confirmed" and does not itself
// transition room status or publish chat.closed (T037/T039's job).
public record MarkMetOutcome(MarkMetResult Result, bool BothUsersMet, string? ErrorMessage);
