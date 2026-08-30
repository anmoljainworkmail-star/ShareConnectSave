namespace user_service.Services.Interfaces;

// The result of a successful validation - deliberately NOT the raw Google
// response. Only the four fields user-service actually needs cross this
// boundary; everything else Google's tokeninfo endpoint returns (iss, exp,
// azp, iat, ...) is consumed and discarded inside the validator, never
// carried further into the rest of the app.
public record GoogleTokenPayload(string Sub, string? Email, string? Name, string? Picture);

// Dependency Inversion (SOLID D): AuthController depends on this interface,
// never on HttpClient or Google's tokeninfo URL directly. A unit test can
// substitute a fake that returns a canned GoogleTokenPayload (or null, for
// the invalid-token path) without making a real network call to Google.
public interface IGoogleTokenValidator
{
    // Returns null - never throws - when the token is missing, malformed,
    // expired, signed by Google for a different OAuth client (aud
    // mismatch), or Google's endpoint is unreachable. The caller (AuthController)
    // turns "null" into the one 401 INVALID_GOOGLE_TOKEN response - it never
    // has to distinguish *why* validation failed, which keeps that decision
    // in one place.
    Task<GoogleTokenPayload?> ValidateAsync(string idToken, CancellationToken cancellationToken = default);
}
