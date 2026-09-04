namespace user_service.Controllers;

using Microsoft.AspNetCore.Mvc;
using user_service.Contracts;
using user_service.Extensions;
using user_service.Services.Interfaces;

// MVC Controller (see AuthController's comment for the full Minimal-API-vs-
// Controllers rationale). POST /users/me/verify-identity resolves "me" from
// HttpContext.TryGetUserId() (JWT Identity rule: header only, never a
// client-supplied id) - same as every other "/users/me" route in this
// service.
//
// Thin controller (same shape as UserProfileController/OtpController): every
// business rule - the photo_url precondition, the face-match call, the
// verified/failed audit row, the badge flip - lives in
// IIdentityVerificationService, not here. This controller only resolves
// identity, calls the service, and maps its Result Object to an HTTP
// response.
[ApiController]
public class IdentityVerificationController(IIdentityVerificationService identityVerificationService) : ControllerBase
{
    [HttpPost("/users/me/verify-identity")]
    public async Task<IActionResult> VerifyIdentity(IFormFile? selfie)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await identityVerificationService.VerifyIdentityAsync(userId, selfie, HttpContext.RequestAborted);

        return outcome.Result switch
        {
            VerifyIdentityResult.Success => Ok(outcome.Profile),
            VerifyIdentityResult.NoBasePhoto => NoBasePhoto(outcome.ErrorMessage!),
            VerifyIdentityResult.Mismatch => IdentityMismatch(outcome.ErrorMessage!),
            VerifyIdentityResult.InvalidRequest => InvalidRequest(outcome.ErrorMessage!),
            VerifyIdentityResult.NotFound => UserNotFound(),
            VerifyIdentityResult.Conflict => UpdateConflict(outcome.ErrorMessage!),
            _ => throw new InvalidOperationException($"Unhandled {nameof(VerifyIdentityResult)}: {outcome.Result}"),
        };
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

    // 422 (not 400): the request is syntactically fine - the multipart body
    // parsed correctly - it is semantically impossible to fulfil given the
    // caller's current profile state. Same distinction OtpController draws
    // between a malformed request (400) and a well-formed one that can't
    // succeed given server-side state (422/429).
    private IActionResult NoBasePhoto(string message) =>
        StatusCode(
            StatusCodes.Status422UnprocessableEntity,
            new ErrorResponse("NO_BASE_PHOTO", message, HttpContext.TraceIdentifier));

    private IActionResult IdentityMismatch(string message) =>
        StatusCode(
            StatusCodes.Status422UnprocessableEntity,
            new ErrorResponse("IDENTITY_MISMATCH", message, HttpContext.TraceIdentifier));

    private IActionResult UpdateConflict(string message) =>
        StatusCode(
            StatusCodes.Status409Conflict,
            new ErrorResponse("UPDATE_CONFLICT", message, HttpContext.TraceIdentifier));
}
