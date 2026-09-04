namespace user_service.Services.Interfaces;

using user_service.Models;

// Dependency Inversion (SOLID D): AuthController asks for IJwtIssuer, never
// for a concrete JwtIssuer or a raw RSA/JwtSecurityTokenHandler call inline.
// Interface Segregation (SOLID I): this is the ONLY surface user-service
// exposes for "produce our own signed token" - it does not also expose
// "validate a token" (this service never validates its own tokens; the
// gateway does that), so callers can't accidentally reach for the wrong
// half of an asymmetric-signing contract.
public interface IJwtIssuer
{
    // sub = user.Id (this service's own bigint id), role = "user",
    // gender = user.Gender - the exact three claims api-gateway's
    // JwtValidationMiddleware requires (see T012). T018 adds a fourth,
    // "status" = user.Status, informationally (not yet required or
    // forwarded by the gateway - see JwtIssuer.Issue's comment) so a PATCH
    // /users/me that changes status can reissue a token that actually
    // reflects it, the same way a gender change already could.
    string IssueAccessToken(User user);

    // A longer-lived, narrower-claim companion token. No refresh-exchange
    // endpoint exists yet (out of scope for T016 - see AGENT notes) so
    // nothing in this service currently accepts this token back in; it is
    // issued now so the response shape T016 specifies is already correct
    // for whichever future ticket builds that endpoint.
    string IssueRefreshToken(User user);

    // Standard JWKS shape: { "keys": [ { kty, use, alg, kid, n, e } ] } -
    // PUBLIC key material only. api-gateway's JwksService fetches exactly
    // this from GET /.well-known/jwks.json and never anything else from
    // this service.
    object GetJwks();
}
