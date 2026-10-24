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
public class UserProfileController(IUserProfileService userProfileService, IAdminAccountService adminAccountService) : ControllerBase
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

    // T049: "What NOT to do" (ticket) — this action NEVER accepts a target
    // user id from the route, query string, or request body (see
    // UpdateFcmTokenRequest's own comment: the type has no such field to
    // accept even if a caller tried). The id this PATCH acts on is always
    // HttpContext.TryGetUserId()'s result, same JWT Identity convention
    // every other /users/me action on this controller already follows — a
    // device only ever registers its OWN token, never another user's.
    [HttpPatch("/users/me/fcm-token")]
    public async Task<IActionResult> UpdateMyFcmToken(UpdateFcmTokenRequest request)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await userProfileService.UpdateFcmTokenAsync(userId, request.FcmToken);

        return outcome.Result switch
        {
            FcmTokenUpdateResult.Success => NoContent(),
            FcmTokenUpdateResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            FcmTokenUpdateResult.NotFound => UserNotFound(),
            FcmTokenUpdateResult.Conflict => UpdateConflict(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(FcmTokenUpdateResult)}: {outcome.Result}"),
        };
    }

    // T049: Internal-only endpoint — excluded from the YARP gateway's
    // "user-route" catch-all, reachable only via direct inter-service calls
    // (notification-service calling http://user-service:8080 directly inside
    // the Docker network). A separate internal endpoint lets us avoid exposing
    // device tokens through the public discovery API — GetPublicProfile returns
    // PublicUserProfileDto (which never includes fcm_token), while
    // notification-service's UserServiceClient calls this instead to look up
    // the token for a specific user by id. Same Database-per-Service principle:
    // the internal endpoint is infrastructure (backend-only contract), the
    // public endpoint is user-facing API (gateway-routed, visible to all
    // authenticated clients).
    [HttpGet("/users/internal/{id:long}/fcm-token")]
    public async Task<IActionResult> GetInternalFcmToken(long id, IConfiguration config)
    {
        // Defense in depth (security review fix): even though this endpoint is
        // nominally "internal-only", guard it with a shared secret header. This
        // protects against misconfiguration at the gateway level (e.g. a catch-all
        // route that was supposed to exclude this endpoint but doesn't). The guard
        // is checked here, not at the gateway, so notification-service must send
        // the correct header for every call, and any external caller that somehow
        // reached this endpoint without the header gets a 401 instead of the token.
        if (!TryAuthorizeInternalCall(config, out var unauthorized))
        {
            return unauthorized!;
        }

        var fcmToken = await userProfileService.GetFcmTokenAsync(id);
        if (fcmToken is null && (await userProfileService.GetPublicProfileAsync(id)) is null)
        {
            // User does not exist if GetFcmTokenAsync returned null AND GetPublicProfileAsync
            // also returns null. GetFcmTokenAsync(id) returning null alone could mean either
            // "user not found" or "user exists but has no token registered" — we need to
            // distinguish these cases. The simpler approach: assume user not found only if
            // both methods return null (a user with a registered token will be found by
            // GetFcmTokenAsync; a user with no token but valid profile exists can be verified
            // by GetPublicProfileAsync).
            return UserNotFound();
        }

        return Ok(new InternalFcmTokenResponse(fcmToken));
    }

    // T102: Trust Boundary / Internal-Only Endpoints — same Docker-network
    // trust boundary as GetInternalFcmToken above, reachable only via
    // direct inter-service calls (admin-service calling
    // http://user-service:8080 directly), never through the YARP gateway and
    // never carrying X-User-Id/X-User-Role (those headers answer "who is the
    // signed-in person", meaningless for a service-to-service call with no
    // signed-in person at all). These three actions close the contract gap
    // flagged in admin-service's UserServiceClient — account standing
    // (Active/Suspended) is admin-only data, so it is projected through
    // InternalAccountStatusResponse, never through PublicUserProfileDto/
    // UserProfileDto (see their own class comments on why those two types
    // structurally cannot carry it).
    [HttpGet("/users/internal")]
    public async Task<IActionResult> GetInternalUsersByAccountStatus([FromQuery] string? accountStatus, IConfiguration config)
    {
        if (!TryAuthorizeInternalCall(config, out var unauthorized))
        {
            return unauthorized!;
        }

        var outcome = await adminAccountService.GetUsersByAccountStatusAsync(accountStatus);

        return outcome.Result switch
        {
            AccountStatusListResult.Success => Ok(outcome.Users),
            AccountStatusListResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(AccountStatusListResult)}: {outcome.Result}"),
        };
    }

    [HttpGet("/users/internal/{id:long}")]
    public async Task<IActionResult> GetInternalAccountStatus(long id, IConfiguration config)
    {
        if (!TryAuthorizeInternalCall(config, out var unauthorized))
        {
            return unauthorized!;
        }

        var profile = await adminAccountService.GetAccountStatusAsync(id);
        return profile is null ? UserNotFound() : Ok(profile);
    }

    [HttpPut("/users/internal/{id:long}/account-status")]
    public async Task<IActionResult> UpdateInternalAccountStatus(long id, UpdateAccountStatusRequest request, IConfiguration config)
    {
        if (!TryAuthorizeInternalCall(config, out var unauthorized))
        {
            return unauthorized!;
        }

        var outcome = await adminAccountService.UpdateAccountStatusAsync(id, request.AccountStatus);

        return outcome.Result switch
        {
            AccountStatusUpdateResult.Success => Ok(outcome.Profile),
            AccountStatusUpdateResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            AccountStatusUpdateResult.NotFound => UserNotFound(),
            AccountStatusUpdateResult.Conflict => UpdateConflict(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(AccountStatusUpdateResult)}: {outcome.Result}"),
        };
    }

    // DRY extraction (T102): GetInternalFcmToken originally inlined this same
    // shared-secret check; now that a second and third internal endpoint need
    // the identical guard, duplicating the five-line check a third and fourth
    // time would be the exact "copy the business rule at every call site"
    // problem this project's service-layer extractions (UserProfileService,
    // IOtpService) already exist to avoid for business rules — this is that
    // same discipline applied to an HTTP-layer cross-cutting concern instead.
    private bool TryAuthorizeInternalCall(IConfiguration config, out IActionResult? unauthorized)
    {
        var expectedKey = config["InternalServiceKey"];
        if (string.IsNullOrEmpty(expectedKey) || !Request.Headers.TryGetValue("X-Internal-Service-Key", out var providedKey) || providedKey != expectedKey)
        {
            unauthorized = StatusCode(
                StatusCodes.Status401Unauthorized,
                new ErrorResponse("INVALID_INTERNAL_KEY", "Internal service authentication failed.", HttpContext.TraceIdentifier));
            return false;
        }

        unauthorized = null;
        return true;
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
