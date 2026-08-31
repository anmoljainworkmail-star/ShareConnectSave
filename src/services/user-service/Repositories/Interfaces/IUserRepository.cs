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

    Task<User> AddAsync(User user);

    Task UpdateAsync(User user);
}
