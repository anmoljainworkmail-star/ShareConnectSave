namespace user_service.Controllers;

using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Mvc;
using user_service.Contracts;
using user_service.Extensions;
using user_service.Services.Interfaces;

// MVC Controller (see AuthController's comment for the full Minimal-API-vs-
// Controllers rationale — unchanged here).
//
// Both actions below are reachable only with a valid JWT. Unlike
// POST /auth/google (which has no token to present yet — that IS the actual
// chicken-and-egg case a public gateway route exists for), a client only
// ever calls these two endpoints AFTER a successful Google sign-in already
// produced one (see T063's flow: LoginComponent completes first, THEN
// OtpVerifyComponent runs, and AuthInterceptor attaches the stored JWT to
// every request from that point on). api-gateway's
// JwtValidationMiddleware.PublicRoutePaths was corrected as part of this
// ticket to drop these two routes — see that file's comment for the full
// note. Being gateway-authenticated is what lets this controller resolve
// "userId" from HttpContext.TryGetUserId() (JWT Identity rule: header only,
// never decode a JWT inside a service) instead of needing some other
// caller-supplied identity, which an unauthenticated endpoint could never
// trust.
[ApiController]
public class OtpController(IOtpService otpService) : ControllerBase
{
    // India-only for now (ShareConnectSave has no other-country launch yet):
    // +91 country code, then a 10-digit mobile number starting 6-9 (India's
    // mobile numbering plan reserves 6-9 as the leading digit; 0-5 are never
    // issued to mobile subscribers). Loosen back to full E.164 when the
    // platform expands to other countries.
    private static readonly Regex PhoneNumberPattern = new(@"^\+91[6-9]\d{9}$", RegexOptions.Compiled);
    private static readonly Regex OtpCodePattern = new(@"^\d{6}$", RegexOptions.Compiled);

    [HttpPost("/auth/otp/send")]
    public async Task<IActionResult> SendOtp(OtpSendRequest request)
    {
        // Guard Clause (project convention): reject an obviously-malformed
        // number before ever touching the DB or spending a Twilio SMS
        // credit on it.
        if (string.IsNullOrWhiteSpace(request.PhoneNumber) || !PhoneNumberPattern.IsMatch(request.PhoneNumber))
        {
            return InvalidRequest("Phone number must be a valid Indian mobile number, e.g. +919876543210.");
        }

        await otpService.SendOtpAsync(request.PhoneNumber, HttpContext.RequestAborted);

        // What NOT to do (ticket-explicit): the response never carries the
        // OTP code itself — only Twilio, and OtpService transiently at
        // generation time, ever see the plaintext.
        return Ok(new OtpSendResponse(Sent: true));
    }

    [HttpPost("/auth/otp/verify")]
    public async Task<IActionResult> VerifyOtp(OtpVerifyRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.PhoneNumber) || !PhoneNumberPattern.IsMatch(request.PhoneNumber))
        {
            return InvalidRequest("Phone number must be a valid Indian mobile number, e.g. +919876543210.");
        }

        if (string.IsNullOrWhiteSpace(request.Code) || !OtpCodePattern.IsMatch(request.Code))
        {
            return InvalidRequest("Code must be exactly 6 digits.");
        }

        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await otpService.VerifyOtpAsync(userId, request.PhoneNumber, request.Code, HttpContext.RequestAborted);

        return outcome.Result switch
        {
            OtpVerificationResult.Verified => Ok(new OtpVerifyResponse(PhoneVerified: true, Status: outcome.UserStatus!)),
            OtpVerificationResult.Locked => OtpLocked(outcome.LockedUntil!.Value),
            OtpVerificationResult.InvalidCode => InvalidOtp(),
            OtpVerificationResult.PhoneAlreadyInUse => PhoneAlreadyInUse(),
            _ => throw new InvalidOperationException($"Unhandled {nameof(OtpVerificationResult)}: {outcome.Result}"),
        };
    }

    private IActionResult InvalidRequest(string message) =>
        StatusCode(
            StatusCodes.Status400BadRequest,
            new ErrorResponse("INVALID_REQUEST", message, HttpContext.TraceIdentifier));

    private IActionResult InvalidOtp() =>
        // Acceptance criterion (T017): 400 + INVALID_OTP — this literal code
        // string is matched by callers/tests, do not rename or reword it.
        StatusCode(
            StatusCodes.Status400BadRequest,
            new ErrorResponse("INVALID_OTP", "The code entered is incorrect or has expired.", HttpContext.TraceIdentifier));

    private IActionResult OtpLocked(DateTime lockedUntil) =>
        // Acceptance criterion (T017): 429 + OTP_LOCKED, with the lockout
        // time surfaced in the response. ErrorResponse (T003's canonical,
        // copied-not-referenced shape) has no field slot for a structured
        // extra value, so the lockout instant is embedded in `message`
        // rather than growing a shape only this one error carries.
        StatusCode(
            StatusCodes.Status429TooManyRequests,
            new ErrorResponse(
                "OTP_LOCKED",
                $"Too many failed attempts. Try again after {lockedUntil:O}.",
                HttpContext.TraceIdentifier));

    private IActionResult PhoneAlreadyInUse() =>
        // Fix (Issue 5): the code was correct, but this phone number is
        // already verified on a different account — a conflict between two
        // accounts, not a bad request from this one. 409 is the standard
        // status for "the request is valid but conflicts with current state".
        StatusCode(
            StatusCodes.Status409Conflict,
            new ErrorResponse("PHONE_ALREADY_IN_USE", "This phone number is already verified on another account.", HttpContext.TraceIdentifier));

    private IActionResult MissingIdentity() =>
        StatusCode(
            StatusCodes.Status401Unauthorized,
            new ErrorResponse("MISSING_IDENTITY", "X-User-Id header is missing or invalid.", HttpContext.TraceIdentifier));
}

// Request/response DTOs co-located with the one controller that uses them —
// same convention as AuthController's GoogleAuthRequest/Response.
// [JsonPropertyName] pins the wire shape explicitly; "phone_number" (not
// "phone") matches the field name api-gateway's OtpPhoneNumberBufferingMiddleware
// already reads out of this exact request body for rate-limit partitioning.
public record OtpSendRequest(
    [property: JsonPropertyName("phone_number")] string PhoneNumber);

public record OtpSendResponse(
    [property: JsonPropertyName("sent")] bool Sent);

public record OtpVerifyRequest(
    [property: JsonPropertyName("phone_number")] string PhoneNumber,
    [property: JsonPropertyName("code")] string Code);

public record OtpVerifyResponse(
    [property: JsonPropertyName("phone_verified")] bool PhoneVerified,
    [property: JsonPropertyName("status")] string Status);
