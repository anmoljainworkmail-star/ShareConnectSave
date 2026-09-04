namespace user_service.Configuration;

// Dependency Inversion (SOLID D), same shape as JwtIssuerOptions/OtpOptions:
// ProfilePhotoStorageService depends on IOptions<ProfilePhotoOptions>, never
// reads configuration keys ad hoc. StoragePath/BaseUrl/MaxSizeBytes are not
// secrets — No Hardcoded Config (project rule) is about connection strings,
// keys, and ports, not tunable non-secret defaults — so, like Jwt/Otp above,
// they live with real defaults in appsettings.json's "ProfilePhoto" section
// and stay overridable via ProfilePhoto__StoragePath etc. for any deployment
// that needs a different value.
public class ProfilePhotoOptions
{
    public const string SectionName = "ProfilePhoto";

    // Relative to the app's content root unless this is already an absolute
    // path (e.g. a mounted volume path in a container). Must stay under
    // wwwroot for the default app.UseStaticFiles() in Program.cs to be able
    // to serve it at BaseUrl below — see that call's comment.
    public string StoragePath { get; set; } = "wwwroot/uploads/photos";

    // The PUBLIC-facing URL prefix returned in photo_url — this is what a
    // client (or api-gateway) requests, not necessarily what
    // app.UseStaticFiles() serves internally. Every route in this service is
    // defined WITHOUT a "/user" prefix (e.g. AuthController's
    // "/auth/google") because api-gateway's YARP "user-route" matches
    // "/user/{**catch-all}" and strips that prefix (PathRemovePrefix) before
    // forwarding — the "/user" segment only exists at the gateway boundary,
    // never inside this service. BaseUrl carries that same "/user" prefix so
    // a photo_url built from it resolves correctly through the gateway
    // (the only path that's actually public in a real deployment), even
    // though app.UseStaticFiles() in Program.cs still maps the physical
    // folder to the un-prefixed "/uploads/photos" — exactly like every other
    // controller route in this service is internally un-prefixed. Hitting
    // this container directly (bypassing the gateway, as local dev's exposed
    // port allows) therefore requires manually dropping "/user" from the
    // path, same as it would for any other endpoint here.
    public string BaseUrl { get; set; } = "/user/uploads/photos";

    public long MaxSizeBytes { get; set; } = 5_242_880; // 5 MB
}
