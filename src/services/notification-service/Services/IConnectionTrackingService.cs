namespace notification_service.Services;

// Dependency Inversion (SOLID D) + Interface Segregation (SOLID I): both
// NotificationHub (recording connect/disconnect) and INotificationDispatcher
// (deciding SignalR-vs-FCM) depend on this narrow interface, never on
// IConnectionMultiplexer/Redis directly — same split this codebase already
// applies everywhere a repository sits between business logic and a storage
// client (e.g. IProcessedEventRepository above it).
//
// "Is this user online" means "connected to ANY replica of this service",
// not "connected to THIS process" — see ConnectionTrackingService's own
// comment for why that forces a shared store (Redis) rather than an
// in-memory dictionary.
public interface IConnectionTrackingService
{
    Task AddConnectionAsync(long userId, string connectionId);

    Task RemoveConnectionAsync(long userId, string connectionId);

    Task<bool> IsUserOnlineAsync(long userId);
}
