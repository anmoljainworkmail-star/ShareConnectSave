namespace user_service.Repositories.Interfaces;

using user_service.Models;

// Interface Segregation (SOLID I): this interface only exposes what a caller
// needing user-profile access could plausibly need. OTP lockout checks and
// identity-verification lookups live behind their own interfaces below —
// a consumer that only cares about OTP never sees a User-shaped method, and
// vice versa. One fat IUserServiceRepository would force every caller to
// depend on (and mock, in tests) methods it never calls.
public interface IUserRepository
{
    Task<User?> GetByIdAsync(long id);

    Task<User?> GetByGoogleIdAsync(string googleId);

    // T017 fix: a phone number identifies one real person — once any account
    // has proven ownership of it (PhoneVerifiedAt set), a second, different
    // account must never be allowed to claim it too. OtpService.VerifyOtpAsync
    // calls this immediately before assigning a phone to the caller's account
    // to check whether some OTHER user already holds it.
    Task<User?> GetByVerifiedPhoneAsync(string phone);

    // T102: admin-only list query ("who is currently in this account
    // state") — the data source behind admin-service's suspended-accounts
    // queue. Kept on THIS interface rather than a separate IAdminRepository:
    // Interface Segregation's boundary here is "what table a caller reads"
    // (every method on IUserRepository touches the same users table), not
    // "which service calls it" — same reasoning as GetByVerifiedPhoneAsync
    // immediately above, which is also a single-purpose query added
    // directly here rather than split out.
    Task<List<User>> GetByAccountStatusAsync(string accountStatus);

    // T105: admin-dashboard aggregate — a single COUNT, not a List<User> like
    // GetByAccountStatusAsync above. Kept on this same interface for the same
    // Interface Segregation reasoning as that method's own comment: every
    // method here touches the same users table, so the boundary is "what
    // table", not "which caller". Counting at the database layer (rather than
    // GetByAccountStatusAsync(...).Count) avoids pulling every active user's
    // full row across the wire just to total them.
    Task<int> CountActiveUsersAsync();

    Task<User> AddAsync(User user);

    Task UpdateAsync(User user);
}
