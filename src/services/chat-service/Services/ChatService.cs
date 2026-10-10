namespace chat_service.Services;

using chat_service.Dtos;
using chat_service.Entities;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;
using MongoDB.Bson;

// Thin Controller / Service Layer (project-wide convention since T018):
// ChatController and ChatHub both stay free of Mongo/SignalR-group business
// rules; every rule lives here instead. See IChatService's own comment for
// the full Dependency Inversion/Single Responsibility reasoning.
public class ChatService : IChatService
{
    // Hard server-side cap (ticket requirement: "Cap N at 50 server-side...
    // do not trust a client-supplied limit above the max"). A caller asking
    // for 500 "recent" messages gets 50, silently clamped - never an error,
    // since an over-large request is a client being greedy, not malformed.
    private const int MaxMessageHistoryLimit = 50;

    private readonly IChatRoomRepository _chatRoomRepository;
    private readonly IMessageRepository _messageRepository;
    private readonly ILogger<ChatService> _logger;

    public ChatService(
        IChatRoomRepository chatRoomRepository,
        IMessageRepository messageRepository,
        ILogger<ChatService> logger)
    {
        _chatRoomRepository = chatRoomRepository;
        _messageRepository = messageRepository;
        _logger = logger;
    }

    public async Task<SendMessageOutcome> SendMessageAsync(string chatId, long senderId, string content)
    {
        // Guard Clause (project convention: early return over nested ifs).
        if (string.IsNullOrWhiteSpace(content))
        {
            return new SendMessageOutcome(SendMessageResult.InvalidRequest, null, "Message content must not be empty.");
        }

        // Guard Clause: reject a malformed chatId before it ever reaches the
        // Mongo driver - see IsWellFormedChatRoomId's comment for why this
        // must run before FindByIdAsync, not after.
        if (!IsWellFormedChatRoomId(chatId))
        {
            return new SendMessageOutcome(SendMessageResult.ChatRoomNotFound, null, "No chat room exists with this id.");
        }

        var room = await _chatRoomRepository.FindByIdAsync(chatId);
        if (room is null)
        {
            return new SendMessageOutcome(SendMessageResult.ChatRoomNotFound, null, "No chat room exists with this id.");
        }

        if (room.UserAId != senderId && room.UserBId != senderId)
        {
            return new SendMessageOutcome(SendMessageResult.NotAParticipant, null, "You are not a participant in this chat.");
        }

        if (room.ClosedAt is not null)
        {
            return new SendMessageOutcome(SendMessageResult.ChatRoomClosed, null, "This chat has already closed.");
        }

        var message = new MessageEntity
        {
            ChatId = chatId,
            SenderId = senderId,
            Content = content,
            SentAt = DateTime.UtcNow,
        };

        // Acceptance criterion: persist BEFORE broadcast. Awaiting this call
        // before ChatHub ever touches Clients.Group(...) is what guarantees
        // a client never sees a message over the wire that failed to
        // persist - there is no parallel "fire both at once" path here.
        await _messageRepository.CreateAsync(message);

        return new SendMessageOutcome(SendMessageResult.Success, MessageDto.FromEntity(message), null);
    }

    public async Task<GetMessagesOutcome> GetRecentMessagesAsync(string chatId, long requestingUserId, int? requestedLimit)
    {
        // Guard Clause: see IsWellFormedChatRoomId's comment - a malformed
        // chatId is routine bad client input, not a 500.
        if (!IsWellFormedChatRoomId(chatId))
        {
            return new GetMessagesOutcome(GetMessagesResult.ChatRoomNotFound, null);
        }

        var room = await _chatRoomRepository.FindByIdAsync(chatId);
        if (room is null)
        {
            return new GetMessagesOutcome(GetMessagesResult.ChatRoomNotFound, null);
        }

        if (room.UserAId != requestingUserId && room.UserBId != requestingUserId)
        {
            return new GetMessagesOutcome(GetMessagesResult.NotAParticipant, null);
        }

        // Open/Closed-flavored clamp: a missing/zero/negative/over-max
        // request all collapse to the same server-owned default (the max),
        // rather than four separate branches - the only thing that varies
        // is a caller asking for FEWER than the max, which passes through
        // unchanged.
        var limit = requestedLimit is > 0 and <= MaxMessageHistoryLimit
            ? requestedLimit.Value
            : MaxMessageHistoryLimit;

        var messages = await _messageRepository.FindRecentByChatIdAsync(chatId, limit);

        return new GetMessagesOutcome(
            GetMessagesResult.Success,
            messages.Select(MessageDto.FromEntity).ToList());
    }

    public async Task<MarkMetOutcome> MarkMetSuccessfullyAsync(string chatId, long userId)
    {
        // Guard Clause: see IsWellFormedChatRoomId's comment - a malformed
        // chatId is routine bad client input, not a 500.
        if (!IsWellFormedChatRoomId(chatId))
        {
            return new MarkMetOutcome(MarkMetResult.ChatRoomNotFound, false, "No chat room exists with this id.");
        }

        var room = await _chatRoomRepository.FindByIdAsync(chatId);
        if (room is null)
        {
            return new MarkMetOutcome(MarkMetResult.ChatRoomNotFound, false, "No chat room exists with this id.");
        }

        // Which field this caller maps to is resolved ONCE, here - see
        // IChatRoomRepository.MarkUserMetSuccessfullyAsync's comment for why
        // the repository itself does not re-derive this.
        bool isUserA;
        if (room.UserAId == userId)
        {
            isUserA = true;
        }
        else if (room.UserBId == userId)
        {
            isUserA = false;
        }
        else
        {
            return new MarkMetOutcome(MarkMetResult.NotAParticipant, false, "You are not a participant in this chat.");
        }

        if (room.ClosedAt is not null)
        {
            return new MarkMetOutcome(MarkMetResult.ChatRoomClosed, false, "This chat has already closed.");
        }

        var updated = await _chatRoomRepository.MarkUserMetSuccessfullyAsync(chatId, isUserA, DateTime.UtcNow);
        var bothUsersMet = updated is not null && updated.UserAMetAt is not null && updated.UserBMetAt is not null;

        // Saga step (system design) - deliberately a TODO-style stub, not a
        // real trigger: this ticket's scope (see T036's "Do NOT implement
        // the full ConnectionLifecycleSaga" note) stops at recording both
        // confirmations. T037 is the ticket that actually transitions this
        // room OPEN -> CLOSING -> CLOSED on a grace timer; T039 is the
        // ticket that gives chat-service its first outbox table and
        // actually publishes chat.closed via Confluent.Kafka. Logging here
        // (rather than silently doing nothing) is what makes this hand-off
        // point visible to whoever picks up T037/T039, instead of a signal
        // that only exists by inference from the two timestamp fields.
        if (bothUsersMet)
        {
            _logger.LogInformation(
                "Both participants of chat {ChatId} have confirmed Met Successfully. " +
                "Chat-close state machine (T037) and chat.closed outbox publish (T039) " +
                "are not implemented yet - no further action is taken by this ticket.",
                chatId);
        }

        return new MarkMetOutcome(MarkMetResult.Success, bothUsersMet, null);
    }

    // T036 fix: ChatHub.JoinRoom's participant gate - collapses "malformed
    // chatId", "room doesn't exist", and "room exists but this user isn't in
    // it" into the single false a group-join either allows or refuses.
    public async Task<bool> IsParticipantAsync(string chatId, long userId)
    {
        if (!IsWellFormedChatRoomId(chatId))
        {
            return false;
        }

        var room = await _chatRoomRepository.FindByIdAsync(chatId);

        return room is not null && (room.UserAId == userId || room.UserBId == userId);
    }

    // Guard Clause helper, shared by every method above that takes a chatId.
    // ChatRoomEntity.Id is [BsonRepresentation(BsonType.ObjectId)], so the
    // Mongo driver builds an ObjectId filter out of chatId under the hood -
    // an invalid-format value (e.g. "abc", "", or anything not 24 hex chars)
    // makes THAT translation throw a FormatException, which would otherwise
    // escape this layer entirely and get caught only by the global 500
    // handler. Checking ObjectId.TryParse first turns "bad client input"
    // back into this service's own routine ChatRoomNotFound/false result,
    // instead of a crash-mid-operation path.
    private static bool IsWellFormedChatRoomId(string chatId) => ObjectId.TryParse(chatId, out _);
}
