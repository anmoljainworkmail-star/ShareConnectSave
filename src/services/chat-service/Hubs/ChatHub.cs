namespace chat_service.Hubs;

using Microsoft.AspNetCore.SignalR;
using chat_service.Dtos;
using chat_service.Extensions;
using chat_service.Services.Interfaces;

// Pattern: Hub as thin coordinator (Single Responsibility, SOLID S) - this
// class's only job is real-time connection/group management. It does NOT
// contain REST/history logic (GET /chats/:id/messages, POST /chats/:id/met
// live on ChatController) and it does NOT talk to MongoDB directly - every
// business rule (participant check, closed-chat guard, persistence) is
// delegated to IChatService (Dependency Inversion, SOLID D), the exact same
// abstraction ChatController depends on. Mixing business logic into hub
// methods would couple domain rules to the SignalR transport layer and make
// them untestable outside a live connection.
//
// Pattern: Observer (GoF), via SignalR groups - Clients.Group("chat:{id}")
// broadcasts to every connection that previously called JoinRoom for that
// chat. This hub has no idea who, or how many clients, are in that group;
// each subscriber reacts independently to the one SendMessage call. Same
// shape as this project's Kafka topics (CLAUDE.md's Observer row), just
// transported over a live WebSocket instead of a message broker.
public class ChatHub : Hub
{
    private readonly IChatService _chatService;
    private readonly ILogger<ChatHub> _logger;

    public ChatHub(IChatService chatService, ILogger<ChatHub> logger)
    {
        _chatService = chatService;
        _logger = logger;
    }

    // T038 addition: a PERSONAL per-user group, distinct from the per-chat
    // groups JoinRoom manages below. A client has a user id the moment its
    // WebSocket handshake carries the gateway-injected X-User-Id header - it
    // does NOT yet have a chatId to JoinRoom with, because no chat room
    // exists until a connection.accepted Kafka event creates one (see
    // Messaging/Handlers/ConnectionAcceptedHandler). This personal group is
    // what lets that handler push a "ChatOpened" notification to a client
    // that has never heard of this chat room before this exact moment.
    //
    // Pattern: Observer (GoF), via SignalR groups - same mechanism this
    // hub's own header comment already describes for per-chat broadcast,
    // just keyed by user id instead of chat id.
    //
    // Deliberately does NOT throw on a missing/invalid X-User-Id, unlike
    // JoinRoom/SendMessage below: a connection with no resolvable identity
    // can still be useful for other purposes this hub may gain later, so
    // this only logs and skips joining the personal group rather than
    // tearing the whole connection down over one missing header.
    public override async Task OnConnectedAsync()
    {
        if (TryGetCallerUserId(out var userId))
        {
            await Groups.AddToGroupAsync(Context.ConnectionId, SignalRGroupNames.User(userId));
        }
        else
        {
            _logger.LogWarning("SignalR connection {ConnectionId} has no resolvable X-User-Id; it will not receive personal notifications.", Context.ConnectionId);
        }

        await base.OnConnectedAsync();
    }

    public async Task JoinRoom(string chatId)
    {
        // T036 fix: without this gate, any authenticated platform user who
        // knows or guesses a chatId could join its SignalR group and
        // silently receive every real-time message broadcast to someone
        // else's private conversation - joining a group is itself the
        // security-sensitive action here, not just sending to one.
        // Mirrors SendMessage's identity + HubException shape exactly.
        if (!TryGetCallerUserId(out var userId))
        {
            throw new HubException("Missing or invalid X-User-Id identity for this connection.");
        }

        if (!await _chatService.IsParticipantAsync(chatId, userId))
        {
            throw new HubException("You are not a participant in this chat.");
        }

        await Groups.AddToGroupAsync(Context.ConnectionId, SignalRGroupNames.Chat(chatId));
    }

    public async Task LeaveRoom(string chatId)
    {
        await Groups.RemoveFromGroupAsync(Context.ConnectionId, SignalRGroupNames.Chat(chatId));
    }

    public async Task SendMessage(string chatId, string content)
    {
        // JWT Identity rule: read X-User-Id from the header the gateway
        // already injected on the HTTP request that negotiated this
        // connection - never decode a JWT here. Context.GetHttpContext()
        // is what exposes that original handshake request's headers to a
        // Hub method; reusing HttpContextExtensions.TryGetUserId (the same
        // helper ChatController uses) keeps identity extraction defined in
        // exactly one place in this service, regardless of transport.
        if (!TryGetCallerUserId(out var senderId))
        {
            throw new HubException("Missing or invalid X-User-Id identity for this connection.");
        }

        var outcome = await _chatService.SendMessageAsync(chatId, senderId, content);
        if (outcome.Result != SendMessageResult.Success)
        {
            // HubException is SignalR's documented way to surface a caller-
            // visible error back to the client's invoke promise - letting
            // some other exception type propagate would instead tear down
            // the connection with an opaque, logged-only server error.
            throw new HubException(outcome.ErrorMessage ?? "Unable to send message.");
        }

        // Persist-before-broadcast (acceptance criterion): outcome.Message
        // only exists because ChatService already awaited the MongoDB
        // insert before returning Success - there is no parallel "insert
        // and broadcast at the same time" path.
        await Clients.Group(SignalRGroupNames.Chat(chatId)).SendAsync("ReceiveMessage", outcome.Message);

        await NotifyOtherParticipantAsync(chatId, outcome);
    }

    // Best-effort second push to the OTHER person's personal group (see
    // NewMessageNotification for the room-vs-personal-group reasoning). It
    // runs AFTER the persisted message was broadcast, and a failure here is
    // swallowed on purpose: the message is already saved and delivered to
    // the room, and an alert popup failing must never turn a successful send
    // into an error for the sender (who would then retry and double-send).
    // Sent even if the recipient is still in the room group -- the client
    // suppresses the popup when it is already looking at that chat, which is
    // a presentation decision the server cannot make (it does not know which
    // screen is open).
    private async Task NotifyOtherParticipantAsync(string chatId, SendMessageOutcome outcome)
    {
        // Guard clauses: nothing to notify without both facts the service
        // resolved. Never true on Success today; kept so a future outcome
        // shape change cannot turn into a NullReferenceException mid-send.
        if (outcome.Message is null || outcome.OtherParticipantId is null || outcome.ConnectionId is null)
        {
            return;
        }

        var notification = new NewMessageNotification(
            chatId,
            outcome.ConnectionId,
            outcome.Message.SenderId,
            BuildPreview(outcome.Message.Content));

        try
        {
            await Clients.Group(SignalRGroupNames.User(outcome.OtherParticipantId.Value))
                .SendAsync("NewMessage", notification);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to push NewMessage for chat {ChatId} to user {UserId}.", chatId, outcome.OtherParticipantId);
        }
    }

    // A popup is a glance, not a reader: cap the text so a 2,000-character
    // message cannot blow up a banner (or leak far more than a lock-screen
    // style preview should show).
    private const int PreviewMaxLength = 80;

    private static string BuildPreview(string content)
    {
        var trimmed = content.Trim();
        return trimmed.Length <= PreviewMaxLength ? trimmed : trimmed[..PreviewMaxLength] + "...";
    }

    private bool TryGetCallerUserId(out long userId)
    {
        userId = 0;
        var httpContext = Context.GetHttpContext();
        return httpContext is not null && httpContext.TryGetUserId(out userId);
    }
}
