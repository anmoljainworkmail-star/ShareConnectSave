using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using api_gateway.Configuration;
using api_gateway.Services;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace api_gateway.Middleware;

// API Gateway pattern: this is the single place in the whole platform that
// ever inspects a JWT. Every downstream service (Chat, Discovery, Rating...)
// trusts the X-User-* headers this middleware injects instead of re-validating
// the token itself — one implementation of "is this request authenticated",
// not eight slightly-different copies of the same logic drifting over time.
public class JwtValidationMiddleware
{
    private readonly RequestDelegate _next;
    private readonly IJwksService _jwksService;
    private readonly JwtOptions _jwtOptions;
    private readonly ILogger<JwtValidationMiddleware> _logger;
    // MapInboundClaims = false: the default (true) silently renames short
    // claim names ("sub", "role", "gender") to legacy long-form ClaimTypes
    // URIs on the resulting ClaimsPrincipal. Since this middleware reads
    // claims by their exact short names below, leaving the default on would
    // make every real token fail claim extraction post-validation.
    private readonly JwtSecurityTokenHandler _tokenHandler = new() { MapInboundClaims = false };

    // Public auth routes are the only endpoints that ever handle Google's raw
    // Sign-In token, and that happens entirely inside user-service — the
    // gateway must let these three through untouched, before any app-JWT
    // exists to validate.
    //
    // Path correction: this middleware runs BEFORE app.MapReverseProxy(), so it
    // sees the request exactly as the client sent it to the gateway — with the
    // "/user" prefix from T011's YARP route table (Match.Path: "/user/{**catch-all}")
    // still attached. YARP's PathRemovePrefix transform only strips that prefix
    // *after* a request is proxied, which is why contracts/openapi/user-service.yaml
    // documents these same endpoints without the prefix (/auth/google, not
    // /user/auth/google) — that file describes what user-service itself receives,
    // not what the gateway receives. Comparing against the un-prefixed path here
    // would never match, silently forcing every login attempt through JWT
    // validation with no token yet to present — a chicken-and-egg lockout.
    private static readonly HashSet<string> PublicRoutePaths = new(StringComparer.OrdinalIgnoreCase)
    {
        "/user/auth/google",
        "/user/auth/otp/send",
        "/user/auth/otp/verify",
    };

    public JwtValidationMiddleware(
        RequestDelegate next,
        IJwksService jwksService,
        IOptions<JwtOptions> jwtOptions,
        ILogger<JwtValidationMiddleware> logger)
    {
        _next = next;
        _jwksService = jwksService;
        _jwtOptions = jwtOptions.Value;
        _logger = logger;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        // Healthcheck as a Dependency Gate (T014), continued from Program.cs:
        // this middleware is a plain `app.Use()` component, not an endpoint-routing
        // filter — it runs in front of EVERY request dispatched to an endpoint,
        // including MapGet("/health"), regardless of the textual order the two
        // are registered in. Docker Compose's healthcheck curls this route with
        // no Authorization header at all (it has no identity — it's asking "is
        // the process alive," not "who is calling"), so without this bypass the
        // container would sit at "unhealthy" forever, 401'd by its own gateway.
        // Kept separate from PublicRoutePaths below: that set is strictly
        // POST-only pre-auth routes; conflating a GET liveness probe into the
        // same list would blur two different reasons a route skips validation.
        if (HttpMethods.IsGet(context.Request.Method) &&
            context.Request.Path.Equals("/health", StringComparison.OrdinalIgnoreCase))
        {
            await _next(context);
            return;
        }

        // Guard Clause (project convention): the public-route check is a single
        // early return, not the main validation logic wrapped in
        // `if (!isPublicRoute) { ... }`. Public routes skip straight to the
        // next middleware — no JWKS fetch, no token parsing, nothing.
        if (IsPublicRoute(context.Request))
        {
            await _next(context);
            return;
        }

        var token = ExtractBearerToken(context.Request);
        if (string.IsNullOrWhiteSpace(token))
        {
            await WriteUnauthorizedAsync(context, "Missing or malformed Authorization header.");
            return;
        }

        ClaimsPrincipal principal;
        try
        {
            var signingKeys = await _jwksService.GetSigningKeysAsync(context.RequestAborted);
            principal = ValidateToken(token, signingKeys);
        }
        catch (SecurityTokenException ex)
        {
            // What NOT to do: never log or echo the raw token, only the
            // reason validation failed. The token itself could still be
            // replayed by whoever reads the log.
            _logger.LogWarning("JWT validation failed: {Reason}", ex.Message);
            await WriteUnauthorizedAsync(context, "Invalid or expired token.");
            return;
        }
        catch (InvalidOperationException ex)
        {
            // Fail closed, not crash: GetSigningKeysAsync throws
            // InvalidOperationException on any JWKS-fetch failure (network
            // error, DNS failure, user-service unreachable). Without this
            // catch, that exception propagates unhandled and produces a bare
            // 500 with no body — violating the project's error-envelope rule.
            // Same logging discipline as above: reason only, never the token.
            _logger.LogWarning("JWT validation failed: unable to fetch signing keys — {Reason}", ex.Message);
            await WriteUnauthorizedAsync(context, "Unable to validate token at this time.");
            return;
        }
        catch (Exception ex)
        {
            // Fail closed on anything not already handled above. Token parsing
            // can throw exception types outside our control (library version
            // upgrades have changed this hierarchy before) — a security boundary
            // must reject unknown failure shapes, not crash on them.
            _logger.LogWarning(ex, "JWT validation failed: unrecognized error during token parsing.");
            await WriteUnauthorizedAsync(context, "Invalid or expired token.");
            return;
        }

        var userId = principal.FindFirstValue("sub");
        var role = principal.FindFirstValue("role");
        var gender = principal.FindFirstValue("gender");

        // JWT Identity contract (non-negotiable, per project architecture
        // rules): every future service depends on these three headers being
        // present and correctly named. A token that validates cryptographically
        // but is missing a required claim is still not a usable identity —
        // fail closed rather than forward a request with a blank/absent header.
        if (string.IsNullOrEmpty(userId) || string.IsNullOrEmpty(role) || string.IsNullOrEmpty(gender))
        {
            _logger.LogWarning("JWT validation failed: token is missing one or more required claims (sub/role/gender).");
            await WriteUnauthorizedAsync(context, "Token is missing required claims.");
            return;
        }

        // ASP.NET Core convention: the validated principal belongs on
        // HttpContext.User, even though nothing downstream of the gateway
        // reads it directly (they only see the injected headers below).
        context.User = principal;

        // Strip the raw JWT — services must never see the token itself, only
        // the identity the gateway already vouched for.
        context.Request.Headers.Remove("Authorization");
        context.Request.Headers["X-User-Id"] = userId;
        context.Request.Headers["X-User-Role"] = role;
        context.Request.Headers["X-User-Gender"] = gender;

        await _next(context);
    }

    private static bool IsPublicRoute(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) &&
        PublicRoutePaths.Contains(request.Path.Value ?? string.Empty);

    private static string? ExtractBearerToken(HttpRequest request)
    {
        var header = request.Headers.Authorization.ToString();
        if (string.IsNullOrEmpty(header) || !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return header["Bearer ".Length..].Trim();
    }

    private ClaimsPrincipal ValidateToken(string token, IReadOnlyList<SecurityKey> signingKeys)
    {
        // Strict validation, no wildcards: issuer/audience are this app's own
        // configured values (Jwt:Issuer / Jwt:Audience, overridable via
        // Jwt__Issuer / Jwt__Audience env vars) — never GOOGLE_CLIENT_ID, and
        // never left unchecked. A token signed by the right key but issued
        // for a different audience is still rejected.
        var validationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = _jwtOptions.Issuer,
            ValidateAudience = true,
            ValidAudience = _jwtOptions.Audience,
            ValidateLifetime = true,
            ValidateIssuerSigningKey = true,
            IssuerSigningKeys = signingKeys,
            ClockSkew = TimeSpan.FromSeconds(30),
            // Pin the accepted signing algorithm explicitly rather than
            // relying on implicit key-type/algorithm-family matching — a
            // token must be RS256-signed, the only algorithm user-service
            // ever uses, or it's rejected regardless of key match.
            ValidAlgorithms = new[] { SecurityAlgorithms.RsaSha256 },
        };

        // Throws SecurityTokenException (or a subclass, e.g.
        // SecurityTokenExpiredException / SecurityTokenInvalidSignatureException)
        // on any failure — caught by the caller, never left to bubble up as an
        // unhandled 500.
        return _tokenHandler.ValidateToken(token, validationParameters, out _);
    }

    private static async Task WriteUnauthorizedAsync(HttpContext context, string message)
    {
        // Error Envelope (project-wide contract): { code, message, traceId }
        // on every error path. The shared record types live in
        // user-service/shared-java-lib and aren't referenced from this
        // project, so this is a local equivalent with the same 3-field shape
        // rather than a one-off ad-hoc error body.
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        context.Response.ContentType = "application/json";

        var errorEnvelope = new
        {
            code = "INVALID_TOKEN",
            message,
            traceId = context.TraceIdentifier,
        };

        await context.Response.WriteAsJsonAsync(errorEnvelope);
    }
}

// Middleware Chain: registering this as `app.UseJwtValidation()` (rather than
// `app.UseMiddleware<JwtValidationMiddleware>()` inline in Program.cs) keeps
// Program.cs reading as a list of pipeline stages — JWT validation today,
// rate limiting (T013) next — instead of exposing the concrete middleware type.
public static class JwtValidationMiddlewareExtensions
{
    public static IApplicationBuilder UseJwtValidation(this IApplicationBuilder app) =>
        app.UseMiddleware<JwtValidationMiddleware>();
}
