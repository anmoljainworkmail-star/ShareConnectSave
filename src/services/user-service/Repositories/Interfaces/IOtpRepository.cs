namespace user_service.Repositories.Interfaces;

using user_service.Models;

// Interface Segregation (SOLID I): OTP lockout state is looked up by phone,
// never by the user's identity columns — this interface's shape mirrors that
// access pattern (and the unique index on OtpAttempt.Phone) instead of
// bundling OTP concerns into IUserRepository.
public interface IOtpRepository
{
    Task<OtpAttempt?> GetByPhoneAsync(string phone);

    Task<OtpAttempt> AddAsync(OtpAttempt attempt);

    Task UpdateAsync(OtpAttempt attempt);
}
