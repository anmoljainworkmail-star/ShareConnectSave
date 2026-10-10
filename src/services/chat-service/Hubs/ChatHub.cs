namespace chat_service.Hubs;

using Microsoft.AspNetCore.SignalR;
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

    public ChatHub(IChatService chatService)
    {
        _chatService = chatService;
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

        await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(chatId));
    }

    public async Task LeaveRoom(string chatId)
    {
        await Groups.RemoveFromGroupAsync(Context.ConnectionId, GroupName(chatId));
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
        await Clients.Group(GroupName(chatId)).SendAsync("ReceiveMessage", outcome.Message);
    }

    private static string GroupName(string chatId) => $"chat:{chatId}";

    private bool TryGetCallerUserId(out long userId)
    {
        userId = 0;
        var httpContext = Context.GetHttpContext();
        return httpContext is not null && httpContext.TryGetUserId(out userId);
    }
}
