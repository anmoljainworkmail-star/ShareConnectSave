namespace api_gateway.Configuration;

// Dependency Inversion (D in SOLID): JwksService and JwtValidationMiddleware
// depend on this typed options object (bound once from config in Program.cs),
// never on IConfiguration directly and never on a literal URL/string. Swapping
// where these values come from (appsettings.json today, a secrets manager
// later) never touches the classes that consume them.
public class JwtOptions
{
    public const string SectionName = "Jwt";

    // This app's own issuer value — the one user-service stamps into every
    // token it signs. Never GOOGLE_CLIENT_ID; Google's identity is consumed
    // and discarded entirely inside user-service's POST /auth/google handler.
    public string Issuer { get; set; } = string.Empty;

    // This app's own audience value — who the token is meant for (this
    // gateway / this platform), independent of Google's client id.
    public string Audience { get; set; } = string.Empty;

    // user-service's own JWKS document — the RSA public key used to verify
    // the app's own RS256-signed tokens. Not Google's JWKS endpoint.
    public string JwksEndpoint { get; set; } = string.Empty;

    // No Hardcoded Config: how long the gateway trusts a cached copy of
    // user-service's signing keys before re-fetching. Signing keys rotate on
    // a schedule, not per-request, so this is deliberately measured in hours.
    public int JwksCacheHours { get; set; } = 24;
}
