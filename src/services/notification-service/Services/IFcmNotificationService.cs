namespace notification_service.Services;

// Callers elsewhere in this service (the Kafka consumers added in later
// Phase 8 tickets) depend on this interface, never on FirebaseMessaging or
// any other concrete Firebase SDK type directly - the same Dependency
// Inversion boundary IUserServiceClient draws one layer down.
public interface IFcmNotificationService
{
    Task SendAsync(string userId, string title, string body, IDictionary<string, string> data);
}
