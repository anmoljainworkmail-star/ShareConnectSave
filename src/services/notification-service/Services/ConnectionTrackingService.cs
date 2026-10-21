using StackExchange.Redis;

namespace notification_service.Services;

// Redis Set-backed implementation of IConnectionTrackingService — a NEW
// concept for this codebase (no file to copy, per this ticket's own notes),
// so the "why" deserves the full plain-English treatment:
//
// The Problem: "is this user currently connected to the SignalR hub" sounds
// like it could just be a boolean flag somewhere in memory. It can't be,
// because this service can run MULTIPLE replicas (that is the entire reason
// T048 added a Redis backplane in the first place — SignalR's own broadcast
// needs to reach connections held by OTHER processes). A plain in-memory
// `HashSet<long>` of "online user ids" would only ever know about
// connections THIS replica happens to be holding; a user connected to
// replica B would look "offline" to replica A, and INotificationDispatcher
// would wrongly fall back to FCM even though a live SignalR connection
// exists one process over.
//
// Why it matters: get this wrong and the "SignalR if connected, else FCM"
// decision silently degrades to "SignalR if connected to the SAME replica
// that happens to run the consumer, else FCM" — a user with the app open
// would still get bothered with a push notification half the time, purely
// based on which pod handled their WebSocket handshake.
//
// How this solves it: track online state as one Redis SET per user,
// `notif:online:{userId}`, holding that user's live SignalR connection ids
// (not a single id, and not a boolean/counter) — every replica reads and
// writes the SAME keyspace, so "online" means "online anywhere", not
// "online here". A Set, specifically, rather than a boolean flag or an
// incrementing counter: a user can hold more than one live connection at
// once (two browser tabs, or a phone + a desktop), and closing just ONE of
// those must not mark the user offline while the other is still live. SADD
// on connect, SREM on disconnect, SCARD > 0 to ask "online at all" — the
// member being removed is exactly the connection id that is actually
// closing, so the count only ever reaches zero once every live connection
// has gone away, self-correcting the exact same way SignalR's own group
// membership already behaves (see NotificationHub.OnConnectedAsync).
//
// Example from our project: a user opens the app on their phone (SADD
// notif:online:42 "conn-A") and, a minute later, also opens it in a browser
// tab (SADD notif:online:42 "conn-B" — now {conn-A, conn-B}). They lock
// their phone, which tears down conn-A (SREM -> {conn-B}); SCARD is still 1,
// so IsUserOnlineAsync(42) still returns true and
// ConnectionRequestedHandler's dispatch correctly keeps using SignalR, not
// FCM, for the still-open browser tab.
//
// Same Redis connection + DB index as ProcessedEventRepository (DB 2) but a
// DIFFERENT key prefix (`notif:online:` vs `processed_events:`) — two
// unrelated concerns sharing one logical database safely, because each
// owns its own namespace of keys within it (see src/infra/README.md's
// updated DB-index table).
public sealed class ConnectionTrackingService : IConnectionTrackingService
{
    private const int ConnectionTrackingDbIndex = 2;

    private readonly IDatabase _database;

    public ConnectionTrackingService(IConnectionMultiplexer redis)
    {
        _database = redis.GetDatabase(ConnectionTrackingDbIndex);
    }

    public Task AddConnectionAsync(long userId, string connectionId) =>
        _database.SetAddAsync(Key(userId), connectionId);

    public Task RemoveConnectionAsync(long userId, string connectionId) =>
        _database.SetRemoveAsync(Key(userId), connectionId);

    public async Task<bool> IsUserOnlineAsync(long userId) =>
        await _database.SetLengthAsync(Key(userId)) > 0;

    private static string Key(long userId) => $"notif:online:{userId}";
}
