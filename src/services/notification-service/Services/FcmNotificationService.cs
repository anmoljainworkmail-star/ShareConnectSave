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

        // Not an error path: a user with no registered device token simply
        // cannot receive a push right now. This is routine/expected on mobile
        // apps or if a user hasn't gone through the onboarding flow (see T047).
        // SignalR remains the only realtime channel for such users; this method
        // degrades to a safe no-op instead of throwing.
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

        // Idempotency (kafka-outbox skill): FCM sends can fail for expected,
        // non-retryable reasons (permanently invalid device token, credential
        // issues, rate limiting, etc.). A thrown exception here would propagate
        // to the Kafka consumer, which would then never commit the offset,
        // stalling that partition forever — a single dead device would block
        // delivery to everyone else subscribed to the same topic.
        //
        // Instead, catch any FCM exception, log it, and return normally so the
        // consumer still records this event as processed and commits the offset.
        // A failed push to one device is not an error state that should block
        // the pipeline — it's a delivery problem to that one device only.
        try
        {
            // FirebaseMessaging.DefaultInstance resolves against the
            // FirebaseApp.DefaultInstance built once in Program.cs (see that
            // file's Singleton comment) - this class never constructs or holds
            // its own FirebaseApp, so every call in the process shares the same
            // underlying credentials/connection.
            await FirebaseMessaging.DefaultInstance.SendAsync(message);
        }
        catch (Exception ex)
        {
            // Redact the full device token in logs — logging PII or sensitive
            // credentials (even indirectly via FCM tokens) violates privacy and
            // security best practices. Log only the last 6 characters as a debug
            // aid without exposing the full token.
            var tokenSuffix = deviceToken.Length > 6 ? deviceToken[^6..] : "???";
            logger.LogError(
                ex,
                "Failed to send FCM notification to user {UserId} (token ending in {TokenSuffix}); delivery skipped. This device may be permanently unavailable.",
                userId,
                tokenSuffix);
        }
    }
}
