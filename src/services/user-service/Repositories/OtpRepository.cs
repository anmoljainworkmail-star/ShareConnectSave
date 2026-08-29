namespace user_service.Repositories;

using Microsoft.EntityFrameworkCore;
using user_service.Infrastructure;
using user_service.Models;
using user_service.Repositories.Interfaces;

// Data access only — no attempt-count incrementing or lockout-window rules
// here. Those are business decisions that belong to the OTP service logic
// landing in T017; this class just reads and writes rows.
public class OtpRepository : IOtpRepository
{
    private readonly AppDbContext _context;

    public OtpRepository(AppDbContext context) => _context = context;

    public Task<OtpAttempt?> GetByPhoneAsync(string phone) =>
        _context.OtpAttempts.FirstOrDefaultAsync(o => o.Phone == phone);

    public async Task<OtpAttempt> AddAsync(OtpAttempt attempt)
    {
        _context.OtpAttempts.Add(attempt);
        await _context.SaveChangesAsync();
        return attempt;
    }

    public async Task UpdateAsync(OtpAttempt attempt)
    {
        _context.OtpAttempts.Update(attempt);
        await _context.SaveChangesAsync();
    }
}
