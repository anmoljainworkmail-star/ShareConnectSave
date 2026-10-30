namespace api_gateway.Configuration;

// Dependency Inversion (D in SOLID), same shape as JwtOptions above: Program.cs
// binds this once from config, so the allowed-origins list can differ per
// environment (appsettings.Development.json today, a real deployed frontend
// domain later) without touching the CORS wiring itself.
public class CorsOptions
{
    public const string SectionName = "Cors";

    // Empty by default (base appsettings.json) — production must explicitly
    // opt an origin in via its own appsettings/environment override rather
    // than inheriting a localhost value that would never be correct there.
    public string[] AllowedOrigins { get; set; } = [];
}
