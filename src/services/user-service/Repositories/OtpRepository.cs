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
        try
        {
            await _context.SaveChangesAsync();
            return attempt;
        }
        catch (DbUpdateException)
        {
            // EF Core does not auto-detach an entity when SaveChanges throws, so the
            // failed entry stays tracked as "Added". OtpService's retry paths re-fetch
            // via GetByPhoneAsync and call AddAsync/UpdateAsync again on a fresh entity —
            // without detaching here, the ghost entity re-attempts its failed INSERT
            // alongside the retry's write, producing a second, uncaught DbUpdateException.
            // Detaching lets the retry proceed against a clean change tracker while the
            // original exception still propagates unchanged for OtpService to catch.
            _context.Entry(attempt).State = EntityState.Detached;
            throw;
        }
    }

    public async Task UpdateAsync(OtpAttempt attempt)
    {
        _context.OtpAttempts.Update(attempt);
        try
        {
            await _context.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            // Same reasoning as AddAsync: detach so a subsequent GetByPhoneAsync in a
            // retry path re-hydrates a fresh instance (with the current RowVersion)
            // instead of EF Core's identity resolution handing back this same stale,
            // still-tracked instance.
            _context.Entry(attempt).State = EntityState.Detached;
            throw;
        }
    }
}
