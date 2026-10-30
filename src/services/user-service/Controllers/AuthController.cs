namespace user_service.Controllers;

using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Mvc;
using user_service.Contracts;
using user_service.Models;
using user_service.Repositories.Interfaces;
using user_service.Services.Interfaces;

// MVC Controller (switched from Minimal API endpoints in T016's original pass):
// unlike a Minimal API static class + IEndpointRouteBuilder extension method,
// [ApiController] is a real class ASP.NET Core instantiates per request via
// DI, with dependencies constructor-injected once instead of per-method-
// parameter. This is the same shape as a Spring Boot @RestController with
// @Autowired constructor injection - the reason for the switch was to reduce
// the number of unfamiliar routing paradigms to learn at once (Minimal API +
// Spring Boot simultaneously), not a functional requirement of this ticket.
// GET /.well-known/jwks.json lives here too even though it doesn't share the
// "/auth" prefix other actions would - RFC 8615 well-known URIs are a
// standard convention, not a feature-specific route, same reasoning as the
// original Minimal API version's comment.
[ApiController]
public class AuthController(
    IGoogleTokenValidator googleTokenValidator,
    IUserRepository userRepository,
    IJwtIssuer jwtIssuer) : ControllerBase
{
    [HttpPost("/auth/google")]
    public async Task<IActionResult> GoogleSignIn(GoogleAuthRequest request)
    {
        // Guard Clause (project convention): reject obviously-empty input
        // before ever calling out to Google - no network round-trip spent on
        // a request that can't possibly succeed.
        if (string.IsNullOrWhiteSpace(request.IdToken))
        {
            return InvalidGoogleToken();
        }

        // Trust boundary: this is the ONLY line in the entire platform that
        // ever hands a Google ID token to anything. Past this call, Google's
        // token is discarded - only `payload` (our own small typed result)
        // and, from here on, our own issued JWT exist for this session.
        var payload = await googleTokenValidator.ValidateAsync(request.IdToken, HttpContext.RequestAborted);
        if (payload is null)
        {
            return InvalidGoogleToken();
        }

        var existingUser = await userRepository.GetByGoogleIdAsync(payload.Sub);
        var isNewUser = existingUser is null;

        // Upsert (Repository pattern, T015): "does a user exist for this
        // Google account" and "create/find one" both go through
        // IUserRepository - this controller never touches AppDbContext or a
        // DbSet directly.
        User user;
        if (isNewUser)
        {
            user = new User
            {
                GoogleId = payload.Sub,
                Email = payload.Email,
                Name = payload.Name ?? string.Empty,
                PhotoUrl = payload.Picture,

                // Post-T018 fix: onboarding-complete state lives on
                // IsOnboardingComplete (see User.cs's comment), not Status -
                // both default via the entity itself (false / "Unavailable")
                // so a brand-new user never needs this constructor to set
                // them explicitly, same as Gender/PreferredLanguage below.
            };
            user = await userRepository.AddAsync(user);
        }
        else
        {
            user = existingUser!;
        }

        var accessToken = jwtIssuer.IssueAccessToken(user);
        var refreshToken = jwtIssuer.IssueRefreshToken(user);

        return Ok(new GoogleAuthResponse(accessToken, refreshToken, isNewUser));
    }

    // T063: the redemption endpoint IssueRefreshToken's response shape was
    // always built for (see IJwtIssuer's comment) - a public gateway route,
    // same chicken-and-egg reasoning as POST /auth/google: the Authorization
    // header on this call necessarily carries an already-expired access
    // token (that is the entire reason the client is calling this), so the
    // real credential travels in the request body instead and is validated
    // here, not at the gateway.
    [HttpPost("/auth/refresh")]
    public async Task<IActionResult> Refresh(RefreshRequest request)
    {
        // Guard Clause (project convention): reject empty input before ever
        // touching JwtIssuer's RSA validation path.
        if (string.IsNullOrWhiteSpace(request.RefreshToken))
        {
            return InvalidRefreshToken();
        }

        var userId = jwtIssuer.ValidateRefreshToken(request.RefreshToken);
        if (userId is null)
        {
            return InvalidRefreshToken();
        }

        // Re-check the account still exists rather than trusting the token's
        // claims alone: a refresh token is long-lived (30 days by default,
        // see JwtIssuerOptions.RefreshTokenDays) and carries no information
        // about the account's CURRENT state - only that it belonged to this
        // user id at issuance time.
        var user = await userRepository.GetByIdAsync(userId.Value);
        if (user is null)
        {
            return InvalidRefreshToken();
        }

        // Reissue-on-demand (same idea as UserProfileService's "Stale JWT
        // Claim" fix from T018): the new access token reflects the user's
        // CURRENT gender/status/onboarding state, not whatever was true 30
        // days ago when the refresh token was minted.
        //
        // Scope decision (T063, deliberate - not an oversight): the SAME
        // refresh token is returned to the caller unchanged, not rotated.
        // True rotation (issue a new refresh token, invalidate the old one)
        // needs a persisted, revocable token store - this platform has none
        // today, and bolting one on as a side effect of this ticket would be
        // real new infrastructure, not "finishing the auth flow." Logged
        // here rather than silently decided so a future ticket can pick it
        // up deliberately if session-hijacking defense in depth is ever
        // prioritized.
        var accessToken = jwtIssuer.IssueAccessToken(user);
        return Ok(new RefreshResponse(accessToken));
    }

    [HttpGet("/.well-known/jwks.json")]
    public IActionResult GetJwks() => Ok(jwtIssuer.GetJwks());

    private IActionResult InvalidGoogleToken() =>
        // Error Envelope (project-wide contract): { code, message, traceId }.
        // "INVALID_GOOGLE_TOKEN" is a literal string other services/tests
        // match on - do not rename or reword it.
        StatusCode(
            StatusCodes.Status401Unauthorized,
            new ErrorResponse(
                "INVALID_GOOGLE_TOKEN",
                "Google ID token failed validation.",
                HttpContext.TraceIdentifier));

    private IActionResult InvalidRefreshToken() =>
        // Error Envelope (project-wide contract): { code, message, traceId }.
        // "INVALID_REFRESH_TOKEN" is this endpoint's one error code - the
        // Angular client (T063's AuthInterceptor) treats ANY failure here
        // the same way (clear tokens, redirect to login), so this never
        // needs to distinguish "expired" from "forged" from "account gone"
        // for the caller's benefit - only the server log (not this
        // response) needs that detail, and nothing here logs the token
        // itself, by the same discipline as the gateway's own JWT failures.
        StatusCode(
            StatusCodes.Status401Unauthorized,
            new ErrorResponse(
                "INVALID_REFRESH_TOKEN",
                "Refresh token is missing, invalid, or expired.",
                HttpContext.TraceIdentifier));
}

// Request/response DTOs co-located with the one controller that uses them.
// [JsonPropertyName] pins the wire shape explicitly, the same reasoning as
// Contracts/ErrorResponse.cs - never rely on ambient JsonSerializerOptions
// naming policy for a contract other services depend on.
public record GoogleAuthRequest(
    [property: JsonPropertyName("id_token")] string IdToken);

public record GoogleAuthResponse(
    [property: JsonPropertyName("access_token")] string AccessToken,
    [property: JsonPropertyName("refresh_token")] string RefreshToken,
    [property: JsonPropertyName("is_new_user")] bool IsNewUser);

public record RefreshRequest(
    [property: JsonPropertyName("refresh_token")] string RefreshToken);

public record RefreshResponse(
    [property: JsonPropertyName("access_token")] string AccessToken);
