namespace user_service.Services;

using Microsoft.EntityFrameworkCore;
using user_service.Dtos;
using user_service.Repositories.Interfaces;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S) + Dependency Inversion (SOLID D): this
// class owns every admin account-standing business rule (which status
// values are legal, when SuspendedAt gets stamped/cleared) and talks only to
// IUserRepository — never AppDbContext directly — same shape as
// UserProfileService. See IAdminAccountService's own class comment for why
// this is a SEPARATE class from UserProfileService rather than a few extra
// methods bolted onto it.
public class AdminAccountService : IAdminAccountService
{
    // Open/Closed (SOLID O): a future account-status value (e.g. a
    // "PendingReview" hold state) means adding one entry here, not touching
    // the branch logic in GetUsersByAccountStatusAsync/
    // UpdateAccountStatusAsync below — same shape as UserProfileService's
    // AllowedGenders/AllowedStatuses lookups.
    private static readonly Dictionary<string, string> AllowedAccountStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        ["active"] = "Active",
        ["suspended"] = "Suspended",
    };

    private readonly IUserRepository _userRepository;

    public AdminAccountService(IUserRepository userRepository)
    {
        _userRepository = userRepository;
    }

    public async Task<AccountStatusListOutcome> GetUsersByAccountStatusAsync(string? accountStatus)
    {
        // Guard Clause: there is no "list everyone" use case for this
        // internal endpoint today (admin-service only ever calls
        // ?accountStatus=suspended) — an absent or unrecognized filter is
        // routine invalid input, not a request to dump the whole table.
        if (string.IsNullOrWhiteSpace(accountStatus) || !AllowedAccountStatuses.TryGetValue(accountStatus, out var normalized))
        {
            return new AccountStatusListOutcome(
                AccountStatusListResult.InvalidRequest,
                $"accountStatus query parameter must be one of: {string.Join(", ", AllowedAccountStatuses.Keys)}.",
                null);
        }

        var users = await _userRepository.GetByAccountStatusAsync(normalized);
        return new AccountStatusListOutcome(
            AccountStatusListResult.Success,
            null,
            users.Select(InternalAccountStatusResponse.FromUser).ToList());
    }

    public async Task<InternalAccountStatusResponse?> GetAccountStatusAsync(long userId)
    {
        var user = await _userRepository.GetByIdAsync(userId);
        return user is null ? null : InternalAccountStatusResponse.FromUser(user);
    }

    public async Task<AccountStatusUpdateOutcome> UpdateAccountStatusAsync(long userId, string? accountStatus)
    {
        if (string.IsNullOrWhiteSpace(accountStatus) || !AllowedAccountStatuses.TryGetValue(accountStatus, out var normalized))
        {
            return new AccountStatusUpdateOutcome(
                AccountStatusUpdateResult.InvalidRequest,
                $"account_status must be one of: {string.Join(", ", AllowedAccountStatuses.Keys)}.",
                null);
        }

        var user = await _userRepository.GetByIdAsync(userId);
        if (user is null)
        {
            return new AccountStatusUpdateOutcome(AccountStatusUpdateResult.NotFound, null, null);
        }

        user.AccountStatus = normalized;

        // SuspendedAt mirrors User.PhoneVerifiedAt's "null means never
        // happened" convention (see User.SuspendedAt's own comment): stamped
        // only on the transition INTO Suspended, cleared the moment the
        // account leaves that state, so this column always answers "since
        // when has this account been suspended RIGHT NOW" — never a stale
        // timestamp from an earlier suspension a later reinstatement already
        // undid.
        user.SuspendedAt = normalized == "Suspended" ? DateTime.UtcNow : null;

        try
        {
            await _userRepository.UpdateAsync(user);
        }
        catch (DbUpdateConcurrencyException)
        {
            // Optimistic Concurrency (see User.RowVersion's class comment):
            // same reasoning as every identical catch in UserProfileService
            // — an admin action racing a self-service PATCH (or a second,
            // concurrent admin action) on the same row must throw here
            // instead of one write silently clobbering the other's change.
            return new AccountStatusUpdateOutcome(
                AccountStatusUpdateResult.Conflict,
                "User account was modified concurrently. Please fetch the latest version and retry.",
                null);
        }

        return new AccountStatusUpdateOutcome(AccountStatusUpdateResult.Success, null, InternalAccountStatusResponse.FromUser(user));
    }
}
