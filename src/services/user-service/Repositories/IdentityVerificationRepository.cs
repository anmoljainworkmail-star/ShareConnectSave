namespace user_service.Repositories;

using Microsoft.EntityFrameworkCore;
using user_service.Infrastructure;
using user_service.Models;
using user_service.Repositories.Interfaces;

// Data access only — verification-status transitions (Pending -> Verified,
// etc.) are business rules that belong to T019, not here.
public class IdentityVerificationRepository : IIdentityVerificationRepository
{
    private readonly AppDbContext _context;

    public IdentityVerificationRepository(AppDbContext context) => _context = context;

    public Task<IdentityVerification?> GetByUserIdAsync(long userId) =>
        _context.IdentityVerifications.FirstOrDefaultAsync(v => v.UserId == userId);

    public async Task<IdentityVerification> AddAsync(IdentityVerification verification)
    {
        _context.IdentityVerifications.Add(verification);
        await _context.SaveChangesAsync();
        return verification;
    }

    public async Task UpdateAsync(IdentityVerification verification)
    {
        _context.IdentityVerifications.Update(verification);
        await _context.SaveChangesAsync();
    }
}
