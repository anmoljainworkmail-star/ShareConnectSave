using api_gateway.Configuration;
using api_gateway.Middleware;
using api_gateway.Services;

var builder = WebApplication.CreateBuilder(args);

// No Hardcoded Config: builder.Configuration already reads appsettings.json,
// appsettings.{Environment}.json and environment variables in that order, so
// Docker Compose can override any ReverseProxy value at deploy time without a
// code change or rebuild.
builder.Configuration.AddEnvironmentVariables();

// API Gateway pattern + Dependency Inversion (D in SOLID): the gateway depends
// on the *shape* of a route/cluster config, not on hardcoded knowledge of
// where each of the 8 downstream services lives. LoadFromConfig binds the
// "ReverseProxy" section (Routes + Clusters) from appsettings.json. Adding a
// 9th service later means adding config, not touching this file — that's the
// Open/Closed Principle in practice: the routing engine here never changes.
builder.Services.AddReverseProxy()
    .LoadFromConfig(builder.Configuration.GetSection("ReverseProxy"));

// Dependency Inversion (D in SOLID): bind the "Jwt" section once, here, into a
// typed JwtOptions. JwksService and JwtValidationMiddleware then depend on
// IOptions<JwtOptions>, never on IConfiguration or a literal string — the
// same reason ReverseProxy config is bound instead of read ad-hoc above.
builder.Services.Configure<JwtOptions>(builder.Configuration.GetSection(JwtOptions.SectionName));

// Typed HttpClient: lets the framework own HttpClient pooling/DNS-refresh
// lifetime instead of JwksService holding a single long-lived instance itself
// (the classic "new HttpClient() per call" / socket-exhaustion pitfall this
// factory pattern exists to avoid).
builder.Services.AddHttpClient<IJwksService, JwksService>();

var app = builder.Build();

// Middleware Chain: JWT validation is registered BEFORE app.MapReverseProxy().
// Order here is not cosmetic — if it ran after, an unauthenticated or expired
// request would already have been forwarded to a downstream service before
// the gateway ever got a chance to reject it. Rate limiting (T013) will slot
// in as another stage in this same chain.
app.UseJwtValidation();

// Config-driven proxying: every request either matches one of the 8 routes in
// appsettings.json and gets forwarded to its cluster, or matches nothing and
// falls through to ASP.NET Core's default 404 — no MapFallback/catch-all is
// registered here on purpose, so unmatched routes aren't swallowed before
// YARP (or the framework) gets to return that 404.
app.MapReverseProxy();

app.Run();
