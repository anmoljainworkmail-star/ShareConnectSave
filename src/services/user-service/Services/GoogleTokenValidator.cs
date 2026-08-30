namespace user_service.Services;

using System.Net.Http.Json;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S): this class does exactly one thing - ask
// Google "is this ID token real, and if so, who is it for" - and hands back
// a small typed result. It does not touch the users table, does not issue a
// JWT, does not know AuthController exists. Everything downstream of a
// successful validation (upsert, JWT issuance) lives in its own collaborator.
public class GoogleTokenValidator : IGoogleTokenValidator
{
    // Google's OAuth2 tokeninfo endpoint. This is Google's own signature/
    // expiry/issuer check as a service - we never parse or verify the
    // Google-signed JWT ourselves, which would mean holding Google's public
    // keys and duplicating validation logic Google already runs. What we DO
    // still have to check ourselves is the audience (see ValidateAsync)
    // because tokeninfo happily returns 200 for a token minted for ANY
    // Google OAuth client, not just ours.
    private const string GoogleTokenInfoEndpoint = "https://oauth2.googleapis.com/tokeninfo";

    private readonly HttpClient _httpClient;
    private readonly string _googleClientId;
    private readonly ILogger<GoogleTokenValidator> _logger;

    public GoogleTokenValidator(HttpClient httpClient, IConfiguration configuration, ILogger<GoogleTokenValidator> logger)
    {
        _httpClient = httpClient;
        _logger = logger;

        // No Hardcoded Config: reuses the same GOOGLE_CLIENT_ID env var
        // already scaffolded in .env / .env.example (T004) - no new
        // environment variable introduced for this ticket.
        _googleClientId = configuration["GOOGLE_CLIENT_ID"]
            ?? throw new InvalidOperationException("GOOGLE_CLIENT_ID is not set");
    }

    public async Task<GoogleTokenPayload?> ValidateAsync(string idToken, CancellationToken cancellationToken = default)
    {
        // Guard Clause: don't even make the network call for an obviously
        // empty token.
        if (string.IsNullOrWhiteSpace(idToken))
        {
            return null;
        }

        HttpResponseMessage response;
        try
        {
            var url = $"{GoogleTokenInfoEndpoint}?id_token={Uri.EscapeDataString(idToken)}";
            response = await _httpClient.GetAsync(url, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            // Fail closed: a network failure talking to Google is treated
            // identically to "token is invalid" from the caller's point of
            // view - there is no "let them in anyway" fallback for an
            // unauthenticated request.
            _logger.LogWarning(ex, "Google tokeninfo request failed");
            return null;
        }

        if (!response.IsSuccessStatusCode)
        {
            // Google returns 400 for an expired/malformed/tampered token -
            // that IS the "invalid token" signal, not an exceptional error.
            return null;
        }

        var payload = await response.Content.ReadFromJsonAsync<GoogleTokenInfoResponse>(cancellationToken: cancellationToken);
        if (payload is null || string.IsNullOrEmpty(payload.Sub))
        {
            return null;
        }

        // Audience pinning: Google's tokeninfo endpoint proves the token is
        // genuinely Google-signed and unexpired, but NOT that it was minted
        // for THIS app. Skipping this check would let a valid Google ID
        // token issued to a completely unrelated OAuth client (any app the
        // user has ever signed into with Google) be replayed against our
        // /auth/google endpoint and pass.
        if (!string.Equals(payload.Aud, _googleClientId, StringComparison.Ordinal))
        {
            _logger.LogWarning("Google token audience did not match this app's GOOGLE_CLIENT_ID");
            return null;
        }

        return new GoogleTokenPayload(payload.Sub, payload.Email, payload.Name, payload.Picture);
    }

    // Shape of Google's tokeninfo JSON response. Deliberately private and
    // separate from GoogleTokenPayload (the public contract other classes
    // depend on) - if Google adds/renames fields tomorrow, only this file
    // changes, never IGoogleTokenValidator's callers.
    private sealed record GoogleTokenInfoResponse(
        string? Sub,
        string? Email,
        string? Name,
        string? Picture,
        string? Aud,
        string? Iss);
}
