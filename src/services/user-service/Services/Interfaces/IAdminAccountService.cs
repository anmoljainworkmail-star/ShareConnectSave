namespace user_service.Services.Interfaces;

using user_service.Dtos;

// T102: Single Responsibility (SOLID S) — admin account-standing mutation
// lives behind its OWN interface, never folded into IUserProfileService. A
// suspension an admin sets unilaterally on someone else's row is a
// different kind of change than the self-service edits IUserProfileService
// owns (gender/status/photo, every one of them caller-initiated on their
// OWN row, resolved from X-User-Id). Mixing the two would mean one class
// owns both "what a user may do to themselves" and "what admin-service may
// do to anyone" — exactly the mixing UserProfileController's own class
// comment already calls out as the mistake UserProfileService/Controller
// were split to avoid once before (business rules vs HTTP orchestration);
// this is the same lesson applied to a second axis (self-service vs admin).
//
// Interface Segregation (SOLID I): UserProfileController depends on this
// narrow three-method contract for admin account state, not on a god
// service that also knows how to validate a profile PATCH or save a photo.
public interface IAdminAccountService
{
    Task<AccountStatusListOutcome> GetUsersByAccountStatusAsync(string? accountStatus);

    Task<InternalAccountStatusResponse?> GetAccountStatusAsync(long userId);

    Task<AccountStatusUpdateOutcome> UpdateAccountStatusAsync(long userId, string? accountStatus);
}

// Result Object (same convention as IUserProfileService's ProfileUpdateResult/
// FcmTokenUpdateResult): an invalid or missing ?accountStatus query value is
// routine control flow the controller must branch on, not an exception.
public enum AccountStatusListResult
{
    Success,
    InvalidRequest,
}

public record AccountStatusListOutcome(AccountStatusListResult Result, string? ErrorMessage, IReadOnlyList<InternalAccountStatusResponse>? Users);

public enum AccountStatusUpdateResult
{
    Success,
    InvalidRequest,
    NotFound,
    Conflict,
}

public record AccountStatusUpdateOutcome(AccountStatusUpdateResult Result, string? ErrorMessage, InternalAccountStatusResponse? Profile);
