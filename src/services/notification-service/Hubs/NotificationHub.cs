namespace notification_service.Hubs;

using Microsoft.AspNetCore.SignalR;
using notification_service.Extensions;

// Pattern: Hub as thin coordinator (Single Responsibility, SOLID S) - this
// class's only job is connection/group lifecycle management: who is in
// which personal group. It does not decide WHEN to notify, does not touch
// Kafka, and does not contain any FCM logic (that lives in T047's
// IFcmNotificationService and the Kafka consumers later Phase 8 tickets
// add) - mixing that judgment into the hub would couple domain/business
// rules to the SignalR transport layer and make them untestable outside a
// live connection, same reasoning as chat-service's ChatHub.
//
// Pattern: Observer (GoF), via SignalR groups - Clients.Group("user:{id}")
// (called by later tickets' Kafka consumers via IHubContext<NotificationHub>,
// not by this class) broadcasts to every connection that previously joined
// that personal group in OnConnectedAsync below. This hub has no idea who,
// or how many clients, are listening - each subscriber reacts
// independently. Same shape as this project's Kafka topics and as
// chat-service's ChatHub personal-group push, just the Notification
// Service's half of it: the foreground (open-app, live WebSocket) channel
// that complements T047's FCM background (closed-app) channel.
//
// Unlike ChatHub, this hub has NO client-invokable methods. ChatHub needs
// JoinRoom/SendMessage because chat rooms are created dynamically and a
// client must ask to join one; a user's own personal notification group is
// not something a client ever needs to request - it is implicit in who
// they are, so the only join that should ever happen is the server adding
// them to their OWN group on connect. Deliberately not adding a generic
// "JoinGroup(groupName)" method here: that would let one authenticated
// user ask to join another user's "user:{id}" group and eavesdrop on
// notifications meant for someone else.
public class NotificationHub : Hub
{
    private readonly ILogger<NotificationHub> _logger;

    public NotificationHub(ILogger<NotificationHub> logger)
    {
        _logger = logger;
    }

    // Deliberately does NOT throw on a missing/invalid X-User-Id, mirroring
    // chat-service's ChatHub.OnConnectedAsync exactly: a connection with no
    // resolvable identity can still be useful for other purposes, so this
    // only logs a warning and skips joining the personal group rather than
    // tearing the whole connection down over one missing header.
    public override async Task OnConnectedAsync()
    {
        if (TryGetCallerUserId(out var userId))
        {
            await Groups.AddToGroupAsync(Context.ConnectionId, SignalRGroupNames.User(userId));
        }
        else
        {
            _logger.LogWarning(
                "SignalR connection {ConnectionId} has no resolvable X-User-Id; it will not receive personal notifications.",
                Context.ConnectionId);
        }

        await base.OnConnectedAsync();
    }

    // JWT Identity rule: read X-User-Id from the header the gateway already
    // injected on the HTTP request that negotiated this connection - never
    // decode a JWT here. Context.GetHttpContext() exposes that original
    // handshake request's headers to a Hub method; reusing
    // HttpContextExtensions.TryGetUserId keeps identity extraction defined
    // in exactly one place in this service, regardless of transport.
    private bool TryGetCallerUserId(out long userId)
    {
        userId = 0;
        var httpContext = Context.GetHttpContext();
        return httpContext is not null && httpContext.TryGetUserId(out userId);
    }
}
