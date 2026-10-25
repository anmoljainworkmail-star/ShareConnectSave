namespace user_service.Repositories;

using Microsoft.EntityFrameworkCore;
using user_service.Infrastructure;
using user_service.Models;
using user_service.Repositories.Interfaces;

// Dependency Inversion (SOLID D): this class is the only thing in the
// codebase that knows AppDbContext exists. Every future caller (T016-T019
// endpoints) injects IUserRepository, not AppDbContext or UserRepository
// directly — so a unit test can substitute an in-memory fake without
// spinning up SQL Server, and this is the only place that would need to
// change if the ORM were ever swapped.
public class UserRepository : IUserRepository
{
    private readonly AppDbContext _context;

    public UserRepository(AppDbContext context) => _context = context;

    public Task<User?> GetByIdAsync(long id) =>
        _context.Users.FirstOrDefaultAsync(u => u.Id == id);

    public Task<User?> GetByGoogleIdAsync(string googleId) =>
        _context.Users.FirstOrDefaultAsync(u => u.GoogleId == googleId);

    public Task<User?> GetByVerifiedPhoneAsync(string phone) =>
        _context.Users.FirstOrDefaultAsync(u => u.Phone == phone && u.PhoneVerifiedAt != null);

    public Task<List<User>> GetByAccountStatusAsync(string accountStatus) =>
        _context.Users.Where(u => u.AccountStatus == accountStatus).ToListAsync();

    // T105: Two-Different-Axes Query Logic — AccountStatus (account standing)
    // and IsOnboardingComplete (profile/identity verification progress) are
    // independent dimensions on the same row (a user can be Active but still
    // mid-onboarding, or fully onboarded but later suspended), so "active in
    // circulation" for this metric requires BOTH conditions, not either
    // alone. Filtered here, at the database layer, so SQL Server returns one
    // integer rather than this service materializing every row to count them
    // in memory.
    public Task<int> CountActiveUsersAsync() =>
        _context.Users.CountAsync(u => u.AccountStatus != "Suspended" && u.IsOnboardingComplete);

    public async Task<User> AddAsync(User user)
    {
        _context.Users.Add(user);
        await _context.SaveChangesAsync();
        return user;
    }

    public async Task UpdateAsync(User user)
    {
        _context.Users.Update(user);
        await _context.SaveChangesAsync();
    }
}
