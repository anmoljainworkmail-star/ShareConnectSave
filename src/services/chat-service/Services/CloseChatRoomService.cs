namespace chat_service.Services;

using System.Text.Json;
using chat_service.Dtos;
using chat_service.Entities;
using chat_service.Events;
using chat_service.Hubs;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;
using Microsoft.AspNetCore.SignalR;

// The ONE place in this codebase that implements "what closing a chat room
// means" - see ICloseChatRoomService's comment for the Single
// Responsibility / shared-routine-over-duplication reasoning. Both of
// T037's close triggers (ChatLifecycleService's grace-timer continuation,
// ChatAutoCloseHostedService's 2-hour poll) call this class and nothing
// else; neither inlines its own copy of "delete messages, enqueue the
// event, flip status".
public class CloseChatRoomService : ICloseChatRoomService
{
    private readonly IChatRoomRepository _chatRoomRepository;
    private readonly IMessageRepository _messageRepository;
    private readonly IHubContext<ChatHub> _hubContext;
    private readonly ILogger<CloseChatRoomService> _logger;

    public CloseChatRoomService(
        IChatRoomRepository chatRoomRepository,
        IMessageRepository messageRepository,
        IHubContext<ChatHub> hubContext,
        ILogger<CloseChatRoomService> logger)
    {
        _chatRoomRepository = chatRoomRepository;
        _messageRepository = messageRepository;
        _hubContext = hubContext;
        _logger = logger;
    }

    public async Task CloseRoomAsync(string chatId, string closedReason)
    {
        var room = await _chatRoomRepository.FindByIdAsync(chatId);

        // Guard Clause: a grace timer or poller tick can legitimately fire
        // for an id that no longer resolves to anything actionable - this is
        // routine control flow for a background trigger, not a 500.
        if (room is null)
        {
            _logger.LogDebug("CloseRoomAsync called for unknown chat {ChatId}; nothing to close.", chatId);
            return;
        }

        // Guard Clause: idempotency in C#, BEFORE touching Mongo again - the
        // database-level guard in ChatRoomRepository.CloseRoomAsync's filter
        // is the one that actually matters for correctness under a race, but
        // checking here too avoids an unnecessary message-delete call (and a
        // log line that would otherwise claim "closed" for a room that was
        // already closed minutes ago).
        if (room.Status == ChatRoomStatus.CLOSED)
        {
            _logger.LogDebug("Chat {ChatId} is already CLOSED; CloseRoomAsync is a no-op.", chatId);
            return;
        }

        var closedAtUtc = DateTime.UtcNow;

        // Hard, non-negotiable requirement (REQUIREMENTS.md / CLAUDE.md's
        // responsibility-boundary section): explicit delete, never rely on
        // the TTL index alone - see IMessageRepository.DeleteByChatIdAsync's
        // comment for why TTL-only is a window, not a guarantee.
        //
        // Ordering choice: this runs BEFORE the atomic room-close write
        // below. If this service crashes between the two steps, the room is
        // left OPEN/CLOSING with its messages already gone - a harmless
        // zombie room (re-closeable later by the other trigger, or stuck
        // pending a future manual cleanup), not a room marked CLOSED while
        // messages it promised to erase are still sitting in MongoDB. The
        // reverse ordering would risk exactly the privacy failure this
        // ticket exists to prevent.
        await _messageRepository.DeleteByChatIdAsync(chatId);

        // Mongo-native Outbox Pattern (Tech Lead architecture decision for
        // T037 - see ChatRoomEntity's comment for the full "why not a
        // separate outbox collection" reasoning). The payload is built here,
        // in C#, from data read a moment ago above - that read can go stale
        // between here and the write below, but UserAId/UserBId/ConnectionId
        // never change after a room is created, so staleness of exactly
        // those three fields is harmless. What MUST be atomic - Status,
        // ClosedAt, and the outbox fields landing together or not at all -
        // is handled entirely inside the single FindOneAndUpdateAsync call
        // this method makes below, not by anything in this paragraph.
        var payload = JsonSerializer.Serialize(new ChatClosedEvent(
            EventId: Guid.NewGuid(),
            ChatId: chatId,
            ConnectionId: room.ConnectionId,
            UserAId: room.UserAId.ToString(),
            UserBId: room.UserBId.ToString(),
            ClosedReason: closedReason));

        var closed = await _chatRoomRepository.CloseRoomAsync(
            chatId,
            closedAtUtc,
            closedReason: closedReason,
            outboxEventType: "chat.closed",
            outboxPayload: payload,
            outboxCreatedAtUtc: closedAtUtc);

        if (closed is null)
        {
            // Lost a race to a concurrent close trigger between our read at
            // the top of this method and this write - see
            // ChatRoomRepository.CloseRoomAsync's filter comment. Messages
            // are already deleted either way (DeleteByChatIdAsync above is
            // itself idempotent - deleting an already-empty collection scope
            // is a no-op), so nothing further to do.
            _logger.LogDebug("Chat {ChatId} was closed by a concurrent trigger; this call's room-close write was skipped.", chatId);
            return;
        }

        // "Published" here means "durably enqueued via the Mongo-native
        // outbox fields" - the actual Kafka send is T039's job (its
        // BackgroundService polls for OutboxPublishedAt == null). Logging
        // that distinction explicitly is what keeps this hand-off visible to
        // whoever picks up T039, the same reasoning ChatService.
        // MarkMetSuccessfullyAsync's own T037 hand-off log line already
        // applies.
        _logger.LogInformation(
            "Chat {ChatId} closed (reason={ClosedReason}) and messages deleted. " +
            "chat.closed event durably enqueued via the Mongo-native outbox fields; " +
            "Kafka relay is T039's responsibility, not yet implemented.",
            chatId, closedReason);

        await NotifyBothUsersClosedAsync(room, chatId, closedReason);
    }

    // Pattern: Observer (via events) - the outbox event above tells OTHER
    // SERVICES (Rating) the chat closed; this push tells the two CLIENTS. They
    // are two independent subscribers of the same fact, and neither knows the
    // other exists. Without this push the Angular app has no way to learn a
    // chat ended (it only listens on the hub, not on Kafka) and would leave
    // the user staring at a dead chat screen instead of moving to rating.
    //
    // Sent to each user's PERSONAL group (user:{id}), not chat:{id}: a client
    // that navigated away from the chat screen has left the per-chat group
    // but must still be told to go rate.
    //
    // Best-effort by design: the room is already CLOSED and chat.closed is
    // durably in the outbox, so a failed push (hub down, client offline) must
    // never fail or retry the close itself. An offline client simply learns
    // on its next lookup/reconnect; the authoritative state change is done.
    private async Task NotifyBothUsersClosedAsync(ChatRoomEntity room, string chatId, string closedReason)
    {
        var notification = new ChatClosedNotification(chatId, room.ConnectionId, closedReason);

        try
        {
            await _hubContext.Clients.Group(SignalRGroupNames.User(room.UserAId))
                .SendAsync("ChatClosed", notification);
            await _hubContext.Clients.Group(SignalRGroupNames.User(room.UserBId))
                .SendAsync("ChatClosed", notification);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to push ChatClosed for chat {ChatId}; the close itself already succeeded.", chatId);
        }
    }
}
