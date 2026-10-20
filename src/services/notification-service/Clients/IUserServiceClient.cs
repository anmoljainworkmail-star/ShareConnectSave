namespace notification_service.Clients;

// Dependency Inversion (SOLID D): FcmNotificationService depends on this
// interface, never on HttpClient or a concrete UserServiceClient directly.
// That is what makes it possible to swap the real HTTP implementation for a
// fake in a future test, or wrap it in a Decorator (the same Circuit Breaker
// shape Discovery Service's IUserServiceClient uses around its own User
// Service calls - see T045), without touching a single line of
// FcmNotificationService.
public interface IUserServiceClient
{
    // Returns null when the user has no reachable device token (unknown
    // user, User Service unavailable, or - today - User Service has no
    // device-token field at all yet; see UserServiceClient's comment).
    // Null, not an exception, because "can't push to this user right now" is
    // an expected, routine outcome here, not a failure of the calling code.
    Task<string?> GetFcmDeviceTokenAsync(long userId, CancellationToken cancellationToken = default);
}
