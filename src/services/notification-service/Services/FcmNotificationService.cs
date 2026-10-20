namespace notification_service.Services;

using FirebaseAdmin.Messaging;
using notification_service.Clients;

// Single Responsibility (SOLID S): this class's only job is "send one push
// to one user's device via FCM". It does not decide WHEN a push should fire
// (that business rule belongs to the connection.requested / connection.accepted
// Kafka consumers in later Phase 8 tickets), does not touch Kafka or SignalR,
// and does not know how to look up a device token itself - it asks
// IUserServiceClient for that (Dependency Inversion). Same separation this
// project already applies to TrustScoreCalculator (pure scoring, no DB/Kafka)
// and OutboxRelay (only publishes, no business logic).
public class FcmNotificationService(IUserServiceClient userServiceClient, ILogger<FcmNotificationService> logger)
    : IFcmNotificationService
{
    public async Task SendAsync(string userId, string title, string body, IDictionary<string, string> data)
    {
        // Guard clause over nested ifs (CLAUDE.md rule): a malformed id can
        // never reach a Firebase/User-Service call below.
        if (!long.TryParse(userId, out var parsedUserId))
        {
            logger.LogWarning("SendAsync called with a non-numeric userId {UserId}; push skipped.", userId);
            return;
        }

        var deviceToken = await userServiceClient.GetFcmDeviceTokenAsync(parsedUserId);

        // Not an error path: a user with no registered device - or, today,
        // every user, since User Service has no fcm_token field yet (see
        // UserServiceClient's comment) - simply cannot receive a push right
        // now. SignalR remains the only realtime channel until that field
        // exists; this method degrades to a safe no-op instead of throwing.
        if (string.IsNullOrWhiteSpace(deviceToken))
        {
            logger.LogInformation("No FCM device token available for user {UserId}; push skipped.", userId);
            return;
        }

        var message = new Message
        {
            Token = deviceToken,
            Notification = new Notification { Title = title, Body = body },
            // FirebaseAdmin's Message.Data is IReadOnlyDictionary<string,string>,
            // while the ticket's public SendAsync signature takes the more
            // permissive IDictionary<string,string> (Interface Segregation:
            // callers shouldn't have to care that the SDK wants a read-only
            // view). Copying into a plain Dictionary - which implements
            // IReadOnlyDictionary too - is the explicit conversion, done here
            // rather than by changing that caller-facing signature.
            Data = new Dictionary<string, string>(data),
        };

        // FirebaseMessaging.DefaultInstance resolves against the
        // FirebaseApp.DefaultInstance built once in Program.cs (see that
        // file's Singleton comment) - this class never constructs or holds
        // its own FirebaseApp, so every call in the process shares the same
        // underlying credentials/connection.
        await FirebaseMessaging.DefaultInstance.SendAsync(message);
    }
}
