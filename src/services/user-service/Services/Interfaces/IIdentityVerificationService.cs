namespace user_service.Services.Interfaces;

using Microsoft.AspNetCore.Http;
using user_service.Dtos;

// Single Responsibility (SOLID S), same shape as IUserProfileService/
// IOtpService: this is the narrow contract IdentityVerificationController
// depends on for the entire "prove the selfie matches the base photo and
// flip the badge" business rule. The controller only resolves identity,
// calls this service, and maps its Result Object to an HTTP response - it
// has no idea IFaceMatchService, IBasePhotoDownloadService, or
// IIdentityVerificationRepository even exist.
public interface IIdentityVerificationService
{
    Task<VerifyIdentityOutcome> VerifyIdentityAsync(long userId, IFormFile? selfie, CancellationToken cancellationToken);
}

// Result Object (same convention as IUserProfileService's
// UpdateProfileOutcome/PhotoUploadOutcome): every branch this ticket's
// acceptance criteria calls out - no base photo, a mismatch, a missing/empty
// selfie file, an unknown user - is routine control flow the controller must
// branch on to pick a status code, not an exceptional condition, so it is
// returned, not thrown.
public enum VerifyIdentityResult
{
    Success,
    NoBasePhoto,
    Mismatch,
    InvalidRequest,
    NotFound,
    Conflict,
}

// ErrorMessage/Profile are only populated for the outcome that needs them -
// null in every other case, matching UpdateProfileOutcome/PhotoUploadOutcome's
// convention. Profile (not a raw confidence score or match detail) is all
// that ever reaches the controller on Success - see FaceMatchOutcome's
// comment for why match internals never cross this boundary.
public record VerifyIdentityOutcome(VerifyIdentityResult Result, string? ErrorMessage, UserProfileDto? Profile);
