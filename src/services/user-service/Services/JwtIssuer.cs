namespace user_service.Services;

using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using user_service.Configuration;
using user_service.Models;
using user_service.Services.Interfaces;

// Asymmetric signing (RS256), not a shared secret: this class is the ONLY
// place in the entire platform that ever touches the RSA private key. Every
// other service, including api-gateway, only ever sees the public half via
// GetJwks() below. That asymmetry is the whole point of choosing RS256 over
// HS256 here - with HS256 the gateway would need a copy of the same secret
// used to sign tokens, and anything holding that secret could mint a valid
// token itself. With RS256, a compromised gateway can verify tokens but can
// never forge one.
//
// Singleton (via DI container) - classic pattern from CLAUDE.md's pattern
// table, applied here instead of a hand-rolled `private static readonly`
// keypair: exactly ONE RSA keypair (and one "kid") must exist for this
// process's lifetime. api-gateway's JwksService caches whatever this class
// hands back for up to Jwt:JwksCacheHours (24h by default, see
// api-gateway/appsettings.json) - if this were registered Scoped/Transient
// and re-generated a keypair per request, tokens signed moments apart could
// carry different "kid"s while the gateway is still serving an old cached
// key, and validation would fail unpredictably. AddSingleton in Program.cs
// is what gives this the "one instance for the app's life" guarantee - the
// DI container's version is preferred over a hand-rolled static field
// because it stays swappable/testable like any other injected dependency.
public class JwtIssuer : IJwtIssuer
{
    private readonly RSA _rsa;
    private readonly RsaSecurityKey _signingKey;
    private readonly string _keyId;
    private readonly JwtIssuerOptions _options;

    public JwtIssuer(IOptions<JwtIssuerOptions> options, ILogger<JwtIssuer> logger)
    {
        _options = options.Value;
        _rsa = LoadOrGenerateKey(_options.RsaPrivateKeyPem, logger);

        // kid (Key ID): lets api-gateway's JWKS document carry more than one
        // key during a future rotation window (old key still verifies
        // in-flight tokens, new key signs new ones) without ambiguity about
        // which public key validates which token. Generated once, here, and
        // reused for the life of the process - see the Singleton note above
        // for why that stability matters.
        _keyId = Guid.NewGuid().ToString("N");
        _signingKey = new RsaSecurityKey(_rsa) { KeyId = _keyId };
    }

    private static RSA LoadOrGenerateKey(string? pem, ILogger logger)
    {
        if (!string.IsNullOrWhiteSpace(pem))
        {
            var rsa = RSA.Create();
            rsa.ImportFromPem(pem);
            return rsa;
        }

        // Dev-only fallback, not a shortcut that quietly becomes the
        // production path: generating a keypair in memory means local dev
        // works with zero manual setup, but it is never persisted anywhere.
        // Every process restart produces a brand-new keypair and therefore a
        // brand-new "kid" - any token issued before that restart, and
        // api-gateway's cached copy of the OLD public key, both go stale the
        // instant the process restarts. This is exactly the failure mode RS256
        // + JWKS is supposed to make explicit rather than silent: set
        // Jwt__RsaPrivateKeyPem (see .env.example) to a real, persisted PEM
        // for anything that needs to survive a restart or run as more than
        // one instance.
        logger.LogWarning(
            "Jwt:RsaPrivateKeyPem is not configured - generating an ephemeral in-memory " +
            "RSA keypair for this process. Tokens issued now will fail validation after " +
            "any restart. Set JWT__RSAPRIVATEKEYPEM for anything beyond a single dev session.");
        return RSA.Create(2048);
    }

    public string IssueAccessToken(User user) =>
        Issue(user, TimeSpan.FromMinutes(_options.AccessTokenMinutes), isAccessToken: true);

    public string IssueRefreshToken(User user) =>
        Issue(user, TimeSpan.FromDays(_options.RefreshTokenDays), isAccessToken: false);

    private string Issue(User user, TimeSpan lifetime, bool isAccessToken)
    {
        // JWT Identity contract (project architecture rule, non-negotiable):
        // "sub"/"role"/"gender" are the exact, short claim names
        // api-gateway's JwtValidationMiddleware reads via
        // principal.FindFirstValue("sub"/"role"/"gender") with
        // MapInboundClaims=false. Claim type here is a plain string ("sub"),
        // never ClaimTypes.NameIdentifier - that's what keeps the name
        // intact end-to-end instead of being rewritten to a legacy long-form
        // URI on the receiving side.
        //
        // sub = this service's own bigint identity column (see T015's
        // Primary Keys rationale for BIGINT over UUID) - the ticket text
        // says "our UUID" because it predates that decision; the security
        // property it describes (never Google's sub, always our own id) is
        // unchanged by the concrete type.
        var claims = new List<Claim> { new("sub", user.Id.ToString()) };

        if (isAccessToken)
        {
            claims.Add(new Claim("role", "user"));

            // Gender defaults to "Unspecified" until profile setup (T018)
            // sets a real value. It must never be blank/missing here even
            // for a brand-new user - a missing claim fails the gateway's
            // required-claims guard on every single request that user makes
            // before completing their profile.
            claims.Add(new Claim("gender", user.Gender));
        }
        else
        {
            // Refresh tokens are deliberately narrower than access tokens:
            // role/gender describe an ACTIVE session entitled to call
            // protected endpoints right now. No refresh-exchange endpoint
            // exists yet in this ticket (see IJwtIssuer's comment) - this
            // marker just keeps a future implementation from accidentally
            // accepting an access token where a refresh token belongs, or
            // vice versa.
            claims.Add(new Claim("token_use", "refresh"));
        }

        var token = new JwtSecurityToken(
            issuer: _options.Issuer,
            audience: _options.Audience,
            claims: claims,
            expires: DateTime.UtcNow.Add(lifetime),
            signingCredentials: new SigningCredentials(_signingKey, SecurityAlgorithms.RsaSha256));

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    public object GetJwks()
    {
        // includePrivateParameters: false is the entire security boundary of
        // this method - it exports only Modulus/Exponent (the public half)
        // and there is no code path here that can ever touch the private
        // exponent or primes. Do not "simplify" this to a full key export.
        var publicParameters = _rsa.ExportParameters(includePrivateParameters: false);

        // Standard JWKS shape ({ "keys": [...] }) with n/e base64url-encoded,
        // per RFC 7517 - the same shape api-gateway's JwksService (via
        // JsonWebKeySet) expects to parse from GET /.well-known/jwks.json.
        return new
        {
            keys = new[]
            {
                new
                {
                    kty = "RSA",
                    use = "sig",
                    alg = "RS256",
                    kid = _keyId,
                    n = Base64UrlEncoder.Encode(publicParameters.Modulus),
                    e = Base64UrlEncoder.Encode(publicParameters.Exponent),
                },
            },
        };
    }
}
