using Microsoft.AspNetCore.SignalR;
using notification_service.Dtos;
using notification_service.Hubs;

namespace notification_service.Services;

// Single Responsibility (SOLID S): this class's only job is "turn one
// notification request into either a SignalR push or an FCM push, never
// both, never neither". It does not know anything about Kafka, connection
// lifecycle events, or idempotency — those are each Connection*Handler's job
// (Dependency Inversion, SOLID D: the handlers depend on
// INotificationDispatcher, never on IHubContext<NotificationHub> or
// IFcmNotificationService directly).
//
// Pattern: Strategy-shaped decision, NOT a GoF Strategy object (there is no
// interchangeable algorithm object being swapped at runtime here — just an
// if/else choosing between two concrete delivery mechanisms), so this
// comment deliberately does NOT name a GoF pattern for this branch, per
// CLAUDE.md's "don't force one in" rule. What it DOES apply is the ticket's
// own design intent: look up "is this user online" from a FAST, LOCAL store
// (Redis SCARD via IConnectionTrackingService) rather than a network call to
// another service — the entire reason this decision can live inline in a
// Kafka consumer's hot path without risking a stalled poll loop.
public class NotificationDispatchService(
    IConnectionTrackingService connectionTracking,
    IHubContext<NotificationHub> hubContext,
    IFcmNotificationService fcmNotificationService)
    : INotificationDispatcher
{
    public async Task DispatchAsync(
        long userId,
        string eventName,
        string title,
        string body,
        IDictionary<string, string> data,
        CancellationToken cancellationToken)
    {
        // Fast, local lookup (Redis SCARD on DB 2) — NOT a call to another
        // service, and nothing here blocks the Kafka consumer loop waiting
        // on a network round trip to User Service or anywhere else (see this
        // ticket's explicit "what NOT to do" list).
        var isOnline = await connectionTracking.IsUserOnlineAsync(userId);

        if (isOnline)
        {
            // Observer (GoF, via SignalR groups) — same personal-group push
            // shape as chat-service's ChatOpenedNotification send: this
            // service has no idea how many live connections (tabs/devices)
            // this user currently holds, nor does it need to — every
            // connection that previously joined "user:{id}" in
            // NotificationHub.OnConnectedAsync receives this independently.
            var payload = new NotificationPayload(title, body, new Dictionary<string, string>(data));
            await hubContext.Clients
                .Group(SignalRGroupNames.User(userId))
                .SendAsync(eventName, payload, cancellationToken);
            return;
        }

        // Not connected to ANY replica's hub right now (see
        // IConnectionTrackingService's own comment on what "online" means
        // across multiple replicas) — fall back to the background channel.
        // FcmNotificationService already degrades to a safe no-op if this
        // user has never registered a device token (T047), so this call
        // never needs its own extra guard here.
        await fcmNotificationService.SendAsync(userId.ToString(), title, body, data);
    }
}
