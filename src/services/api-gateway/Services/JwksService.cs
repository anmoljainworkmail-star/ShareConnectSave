using api_gateway.Configuration;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Tokens;

namespace api_gateway.Services;

// Interface Segregation (I in SOLID): callers of this service only ever need
// "give me the current signing keys" — they don't need to know how those keys
// are fetched, cached, or refreshed. Keeping the interface to one method also
// makes it trivial to substitute a fake in a future unit test, without
// standing up a real HTTP endpoint.
public interface IJwksService
{
    Task<IReadOnlyList<SecurityKey>> GetSigningKeysAsync(CancellationToken cancellationToken = default);
}

// Caching for Resilience: user-service's JWKS endpoint hands back the RSA
// public key it uses to sign the app's own JWTs. That key rotates on a
// schedule (think: monthly), not per-request, so re-fetching it on every
// gateway request would (a) add a network hop to the hot path of every
// single API call, and (b) make the gateway's uptime depend on user-service
// being reachable at that exact moment — a hard coupling this pattern removes.
//
// ConfigurationManager<T> is the framework's built-in answer to "fetch once,
// serve from memory, re-fetch only after a configured interval". The library
// ships a retriever for full OpenID Connect discovery documents
// (OpenIdConnectConfigurationRetriever) — that's Google's shape, not
// user-service's — but nothing for a *bare* JWKS document
// ({ "keys": [...] }), which is exactly what user-service's
// /.well-known/jwks.json is. JwksDocumentRetriever below is that missing
// piece: it plugs into the same ConfigurationManager<T> caching machinery,
// just parsing a JsonWebKeySet directly instead of an OIDC document.
public class JwksService : IJwksService
{
    private readonly ConfigurationManager<JsonWebKeySet> _configManager;

    public JwksService(IOptions<JwtOptions> options, HttpClient httpClient)
    {
        var jwtOptions = options.Value;

        _configManager = new ConfigurationManager<JsonWebKeySet>(
            jwtOptions.JwksEndpoint,
            new JwksDocumentRetriever(),
            // RequireHttps = false: user-service's JWKS endpoint is reached over
            // the internal Docker Compose network (http://user-service:8080/...) —
            // there is no TLS between services inside that network. The retriever's
            // default of RequireHttps = true would reject every fetch outright.
            new HttpDocumentRetriever(httpClient) { RequireHttps = false })
        {
            AutomaticRefreshInterval = TimeSpan.FromHours(jwtOptions.JwksCacheHours),
        };
    }

    public async Task<IReadOnlyList<SecurityKey>> GetSigningKeysAsync(CancellationToken cancellationToken = default)
    {
        // GetConfigurationAsync serves the cached copy when it's still fresh,
        // and only awaits a real HTTP call to user-service on the first call
        // or once AutomaticRefreshInterval has elapsed.
        var jwks = await _configManager.GetConfigurationAsync(cancellationToken);
        return jwks.Keys.Cast<SecurityKey>().ToList();
    }

    // Liskov Substitution (L in SOLID): this only has to honor
    // IConfigurationRetriever<JsonWebKeySet>'s contract — "given an address
    // and a document fetcher, hand back a JsonWebKeySet" — so
    // ConfigurationManager<T> can use it interchangeably with any other
    // retriever (including a fake one in a future unit test) without knowing
    // or caring that this one parses a bare JWKS instead of an OIDC document.
    private sealed class JwksDocumentRetriever : IConfigurationRetriever<JsonWebKeySet>
    {
        public async Task<JsonWebKeySet> GetConfigurationAsync(
            string address,
            IDocumentRetriever retriever,
            CancellationToken cancel)
        {
            var json = await retriever.GetDocumentAsync(address, cancel);
            return new JsonWebKeySet(json);
        }
    }
}
