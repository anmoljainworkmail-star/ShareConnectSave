namespace user_service.Controllers;

using Microsoft.AspNetCore.Mvc;
using user_service.Contracts;
using user_service.Dtos;
using user_service.Extensions;
using user_service.Services.Interfaces;

// MVC Controller (see AuthController's comment for the full Minimal-API-vs-
// Controllers rationale — unchanged here).
//
// GET /users/me, PATCH /users/me and POST /users/me/photo all resolve "me"
// from HttpContext.TryGetUserId() (JWT Identity rule: header only, never a
// client-supplied id) — the caller can only ever act on their own row.
// GET /users/{id} is the one action here that takes a caller-supplied id,
// because its whole purpose is looking up SOMEONE ELSE's public card; that
// asymmetry is exactly why it returns a structurally different DTO
// (PublicUserProfileDto) instead of reusing UserProfileDto with fields
// hidden after the fact.
//
// Thin controller (fix): every profile business rule — validation, the
// reissue-on-claim-change decision, photo constraints — now lives in
// IUserProfileService, not here. This controller only resolves identity,
// calls the service, and maps its Result Object to an HTTP response —
// the exact same shape OtpController already used for IOtpService before
// this ticket existed. UserProfileController originally inlined all of that
// logic directly in its actions; that broke Single Responsibility (this
// class owned HTTP orchestration AND business rules at once) in a way the
// rest of this service didn't.
[ApiController]
public class UserProfileController(IUserProfileService userProfileService) : ControllerBase
{
    [HttpGet("/users/me")]
    public async Task<IActionResult> GetMyProfile()
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var profile = await userProfileService.GetProfileAsync(userId);
        return profile is null ? UserNotFound() : Ok(profile);
    }

    [HttpPatch("/users/me")]
    public async Task<IActionResult> UpdateMyProfile(UpdateProfileRequest request)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await userProfileService.UpdateProfileAsync(userId, request);

        return outcome.Result switch
        {
            ProfileUpdateResult.Success => Ok(new UpdateProfileResponse(outcome.Profile!, outcome.NewAccessToken)),
            ProfileUpdateResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            ProfileUpdateResult.NotFound => UserNotFound(),
            ProfileUpdateResult.Conflict => UpdateConflict(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(ProfileUpdateResult)}: {outcome.Result}"),
        };
    }

    [HttpPost("/users/me/photo")]
    public async Task<IActionResult> UploadMyPhoto(IFormFile? photo)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        // requestBaseUrl reflects THIS request's own scheme/host — see
        // IProfilePhotoStorageService's comment for why the storage service
        // can't know this on its own. It's resolved here, not inside
        // UserProfileService, because HttpRequest is an HTTP-layer concept —
        // the service only ever deals in plain strings/IFormFile.
        var requestBaseUrl = $"{Request.Scheme}://{Request.Host}";
        var outcome = await userProfileService.UploadPhotoAsync(userId, photo, requestBaseUrl, HttpContext.RequestAborted);

        return outcome.Result switch
        {
            PhotoUploadResult.Success => Ok(new PhotoUploadResponse(outcome.Profile!, outcome.NewAccessToken)),
            PhotoUploadResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            PhotoUploadResult.NotFound => UserNotFound(),
            PhotoUploadResult.Conflict => UpdateConflict(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(PhotoUploadResult)}: {outcome.Result}"),
        };
    }

    [HttpGet("/users/{id:long}")]
    public async Task<IActionResult> GetPublicProfile(long id)
    {
        var profile = await userProfileService.GetPublicProfileAsync(id);
        return profile is null ? UserNotFound() : Ok(profile);
    }

    private IActionResult InvalidRequest(string message) =>
        StatusCode(
            StatusCodes.Status400BadRequest,
            new ErrorResponse("INVALID_REQUEST", message, HttpContext.TraceIdentifier));

    private IActionResult MissingIdentity() =>
        StatusCode(
            StatusCodes.Status401Unauthorized,
            new ErrorResponse("MISSING_IDENTITY", "X-User-Id header is missing or invalid.", HttpContext.TraceIdentifier));

    private IActionResult UserNotFound() =>
        StatusCode(
            StatusCodes.Status404NotFound,
            new ErrorResponse("USER_NOT_FOUND", "No user exists with this id.", HttpContext.TraceIdentifier));

    private IActionResult UpdateConflict(string message) =>
        StatusCode(
            StatusCodes.Status409Conflict,
            new ErrorResponse("UPDATE_CONFLICT", message, HttpContext.TraceIdentifier));
}
