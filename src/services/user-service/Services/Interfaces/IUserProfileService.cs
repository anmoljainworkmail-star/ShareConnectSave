namespace user_service.Services.Interfaces;

using Microsoft.AspNetCore.Http;
using user_service.Dtos;

// Interface Segregation (SOLID I) + Single Responsibility (SOLID S): this is
// the narrow contract UserProfileController depends on for every profile
// business rule — gender/status validation, the reissue-on-claim-change
// decision, and photo constraints — the same shape IOtpService already gives
// OtpController elsewhere in this service. Before this interface existed,
// UserProfileController inlined all of this directly in its action methods:
// it worked, but left the controller owning both HTTP orchestration AND
// business rules, which is exactly the mixing OtpController/IOtpService
// already avoids. Swapping how profiles are validated or persisted later
// means changing UserProfileService, never the controller.
public interface IUserProfileService
{
    Task<UserProfileDto?> GetProfileAsync(long userId);

    Task<UpdateProfileOutcome> UpdateProfileAsync(long userId, UpdateProfileRequest request);

    Task<PhotoUploadOutcome> UploadPhotoAsync(long userId, IFormFile? photo, string requestBaseUrl, CancellationToken cancellationToken);

    Task<PublicUserProfileDto?> GetPublicProfileAsync(long id);
}

// Result Object (same convention as IOtpService.OtpVerificationResult): an
// invalid PATCH body or a concurrent write conflict is routine control flow
// the controller must branch on to pick a status code — not an exceptional
// condition, so it is returned, not thrown.
public enum ProfileUpdateResult
{
    Success,
    InvalidRequest,
    NotFound,
    Conflict,
}

// ErrorMessage/Profile/NewAccessToken are only populated for the outcome
// that needs them — null in every other case. NewAccessToken carries the
// Stale JWT Claim fix (see UserProfileService.UpdateProfileAsync's comment):
// populated only on Success, and only when gender/status actually changed.
public record UpdateProfileOutcome(ProfileUpdateResult Result, string? ErrorMessage, UserProfileDto? Profile, string? NewAccessToken);

public enum PhotoUploadResult
{
    Success,
    InvalidRequest,
    NotFound,
    Conflict,
}

public record PhotoUploadOutcome(PhotoUploadResult Result, string? ErrorMessage, UserProfileDto? Profile);
