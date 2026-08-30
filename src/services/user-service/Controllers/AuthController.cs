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

                // status = incomplete until phone verification (T017) AND
                // gender (T018) are both set - Discovery Service (later
                // phases) uses this flag to decide who is even eligible to
                // appear in a scan. A brand-new user must never default to
                // anything that looks "ready".
                Status = "incomplete",
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
