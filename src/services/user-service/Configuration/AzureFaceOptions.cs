namespace user_service.Configuration;

// Dependency Inversion (SOLID D), same shape as JwtIssuerOptions/OtpOptions/
// ProfilePhotoOptions: AzureFaceMatchService depends on
// IOptions<AzureFaceOptions>, never reads configuration keys ad hoc. The
// ticket text says "process.env.AZURE_FACE_*" - that's Node.js syntax,
// carried over generically from a shared requirements doc - in this .NET
// service the equivalent is this typed options class bound from the
// "AzureFace" section, itself overridable via the standard ASP.NET Core
// double-underscore env var convention (AzureFace__Endpoint,
// AzureFace__Key), matching every other external-integration config in this
// service.
public class AzureFaceOptions
{
    public const string SectionName = "AzureFace";

    // Non-secret - safe to default in appsettings.json (empty string, filled
    // in per-environment), same reasoning as ProfilePhotoOptions.BaseUrl.
    public string Endpoint { get; set; } = string.Empty;

    // Security-critical secret, deliberately absent from appsettings.json -
    // same "No Hardcoded Config" discipline as JwtIssuerOptions.RsaPrivateKeyPem.
    // Only ever arrives via the AzureFace__Key environment variable (see
    // .env.example). AzureFaceMatchService fails fast at construction if this
    // is missing and IDENTITY_VERIFY_STUB is not set - never silently sends
    // an unauthenticated request to Azure.
    public string? Key { get; set; }
}
