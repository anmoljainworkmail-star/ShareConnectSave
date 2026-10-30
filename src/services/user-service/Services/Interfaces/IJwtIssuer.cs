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

    // A longer-lived, narrower-claim companion token. T016 issued this with
    // no redemption endpoint yet (out of scope at the time); T063 adds the
    // actual redemption path - see ValidateRefreshToken below.
    string IssueRefreshToken(User user);

    // Refresh-token redemption (T063): the ONLY place that ever accepts a
    // refresh token back in. Returns the user id encoded in its "sub" claim
    // when the token is a validly-signed, non-expired, correctly-scoped
    // ("token_use": "refresh") token issued by this same process; null on
    // ANY failure (bad signature, wrong issuer/audience, expired, or an
    // access token handed in where a refresh token belongs - see
    // JwtIssuer.Issue's token_use comment).
    // Interface Segregation (SOLID I): kept as its own method rather than
    // folded into IssueAccessToken/IssueRefreshToken - "redeem a token
    // someone already holds" is a different capability than "mint a new
    // one", so a caller that only ever issues tokens never needs this in
    // its mock.
    long? ValidateRefreshToken(string refreshToken);

    // Standard JWKS shape: { "keys": [ { kty, use, alg, kid, n, e } ] } -
    // PUBLIC key material only. api-gateway's JwksService fetches exactly
    // this from GET /.well-known/jwks.json and never anything else from
    // this service.
    object GetJwks();
}
