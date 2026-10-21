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

    // T049: narrower twin of UpdateProfileAsync's Result Object shape (see
    // FcmTokenUpdateOutcome below) — same enum-driven controller mapping
    // convention, deliberately without a Profile/NewAccessToken payload: a
    // device token is not a JWT claim (JwtIssuer.cs never reads it), so
    // there is nothing here that could ever require a token reissue the way
    // gender/status changes do.
    Task<FcmTokenUpdateOutcome> UpdateFcmTokenAsync(long userId, string? fcmToken);

    // T049: Internal-only lookup for notification-service to retrieve a user's
    // FCM device token. Separated from the public GetProfileAsync to enforce a
    // privacy boundary: the public endpoint returns PublicUserProfileDto (which
    // never includes device tokens), while this narrow method lets only the
    // notification-service's internal HTTP client (UserServiceClient) retrieve
    // the token for dispatch. Interface Segregation (SOLID I): notification-
    // service only needs the token itself, not a full user profile.
    Task<string?> GetFcmTokenAsync(long userId);
}

// Result Object (same convention as ProfileUpdateResult/PhotoUploadResult
// above): a blank token or an unknown user id are both routine control flow
// UserProfileController must branch on to pick a status code, not exceptions.
public enum FcmTokenUpdateResult
{
    Success,
    InvalidRequest,
    NotFound,
    Conflict,
}

public record FcmTokenUpdateOutcome(FcmTokenUpdateResult Result, string? ErrorMessage);

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

// NewAccessToken mirrors UpdateProfileOutcome's field above — populated only
// on Success, and only when uploading this photo was the step that flipped
// IsOnboardingComplete (see User.TryCompleteOnboarding), since
// "onboarding_complete" is a JWT claim (JwtIssuer.cs) too.
public record PhotoUploadOutcome(PhotoUploadResult Result, string? ErrorMessage, UserProfileDto? Profile, string? NewAccessToken);
