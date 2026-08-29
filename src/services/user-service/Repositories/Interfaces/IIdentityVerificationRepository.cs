namespace user_service.Repositories.Interfaces;

using user_service.Models;

// Interface Segregation (SOLID I): identity verification is its own table
// (Single Responsibility at the schema level, per IdentityVerification.cs)
// and gets its own repository to match — a caller checking verification
// status never needs to know how to add or update a User row.
public interface IIdentityVerificationRepository
{
    Task<IdentityVerification?> GetByUserIdAsync(long userId);

    Task<IdentityVerification> AddAsync(IdentityVerification verification);

    Task UpdateAsync(IdentityVerification verification);
}
