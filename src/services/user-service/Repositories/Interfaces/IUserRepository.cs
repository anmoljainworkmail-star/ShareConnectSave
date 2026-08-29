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

    Task<User> AddAsync(User user);

    Task UpdateAsync(User user);
}
