namespace user_service.Configuration;

// Dependency Inversion (SOLID D): JwtIssuer depends on this typed options
// object (bound once from config in Program.cs), never on IConfiguration
// directly. Mirrors api-gateway's own Configuration/JwtOptions.cs, which
// this class's Issuer/Audience values must match EXACTLY (both currently
// "shareconnectsave-platform" / "shareconnectsave-api") - the two services
// never share a signing secret, but they do have to agree on these two
// plain identifiers, or every token minted here fails ValidIssuer /
// ValidAudience at the gateway.
public class JwtIssuerOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = string.Empty;

    public string Audience { get; set; } = string.Empty;

    public int AccessTokenMinutes { get; set; } = 60;

    public int RefreshTokenDays { get; set; } = 30;

    // Security-critical secret, deliberately absent from appsettings.json:
    // this only ever arrives via the Jwt__RsaPrivateKeyPem environment
    // variable (see .env.example). No Hardcoded Config (project rule) means
    // more here than "don't type a literal" - a private signing key must
    // never be checked into a file that a `git diff` or repo clone exposes.
    // If it's null/empty, JwtIssuer falls back to an ephemeral in-memory
    // keypair generated at startup - see JwtIssuer.LoadOrGenerateKey for why
    // that fallback is dev-only, never for anything long-lived or scaled to
    // more than one process.
    public string? RsaPrivateKeyPem { get; set; }
}
