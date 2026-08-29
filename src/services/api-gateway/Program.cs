using System.Threading.RateLimiting;
using Microsoft.Extensions.Caching.Memory;
using api_gateway.Configuration;
using api_gateway.Middleware;
using api_gateway.RateLimiting;
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

// Memory Cache for rate limiter partition registry: required for bounded memory
// growth (sliding expiration disposes expired limiters). IMemoryCache is a built-in
// service; we just need to register it.
builder.Services.AddMemoryCache();

// Dependency Inversion (D in SOLID): depend on the interface, not the concrete class.
// This registry instance is used both inside AddRateLimiter's policies below (to make
// the actual accept/reject decision) and injected into RateLimitHeadersMiddleware (to
// read X-RateLimit-Remaining) — see RateLimitPartitionRegistry.cs for why the SAME
// instance must be used in both places. Registering as singleton guarantees this.
builder.Services.AddSingleton<IRateLimitPartitionRegistry>(sp =>
    new RateLimitPartitionRegistry(sp.GetRequiredService<IMemoryCache>()));

builder.Services.AddRateLimiter(options =>
{
    // Ticket spec: 429, not the framework's default 503, on every rejection —
    // a client that got throttled needs to know it can retry, not that the
    // service is down.
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.OnRejected = RateLimitRejectionHandler.HandleAsync;

    // API Gateway pattern: GlobalLimiter runs first, on every single request
    // this gateway receives, regardless of route — the one place abuse from a
    // misbehaving client gets caught no matter which of the 8 services it's
    // aimed at. Sliding window (vs. the fixed windows below): smooths out
    // burst-at-boundary abuse across the whole client base rather than
    // resetting cleanly every 60s, which a client could otherwise time.
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(httpContext =>
        httpContext.RequestServices.GetRequiredService<IRateLimitPartitionRegistry>()
            .GetSlidingWindowPartition(
                RateLimitPolicies.GlobalPolicy,
                RateLimitPolicies.ResolveIpKey(httpContext),
                permitLimit: RateLimitPolicies.GlobalPolicyPermitLimit,
                window: RateLimitPolicies.GlobalPolicyWindow,
                segmentsPerWindow: RateLimitPolicies.GlobalPolicySegmentsPerWindow));

    // Named policies (as opposed to GlobalLimiter above): these are only enforced
    // where a route explicitly opts in. Wiring is config-driven — see the
    // "RateLimiterPolicy" field on the two new routes in appsettings.json — not
    // hardcoded to an endpoint here, consistent with how ReverseProxy routing
    // itself is config-driven (Open/Closed: adding a route's policy is a config
    // change, not a code change).
    options.AddPolicy<string>(RateLimitPolicies.ConnectionRequestPolicy, httpContext =>
        httpContext.RequestServices.GetRequiredService<IRateLimitPartitionRegistry>()
            .GetFixedWindowPartition(
                RateLimitPolicies.ConnectionRequestPolicy,
                RateLimitPolicies.ResolveUserIdKey(httpContext),
                permitLimit: 10,
                window: TimeSpan.FromMinutes(1)));

    options.AddPolicy<string>(RateLimitPolicies.OtpSendPolicy, httpContext =>
        httpContext.RequestServices.GetRequiredService<IRateLimitPartitionRegistry>()
            .GetFixedWindowPartition(
                RateLimitPolicies.OtpSendPolicy,
                RateLimitPolicies.ResolvePhoneKey(httpContext),
                permitLimit: 5,
                window: TimeSpan.FromMinutes(10)));

    // Follow-up (post-T013 review): closes the login-flood gap noted below — a
    // public route with no dedicated policy was only ever caught by GlobalPolicy's
    // much looser 100/min-per-IP ceiling. Partitioned by IP, not X-User-Id, since
    // this route runs before any identity exists (that's precisely why it's public).
    options.AddPolicy<string>(RateLimitPolicies.GoogleAuthPolicy, httpContext =>
        httpContext.RequestServices.GetRequiredService<IRateLimitPartitionRegistry>()
            .GetFixedWindowPartition(
                RateLimitPolicies.GoogleAuthPolicy,
                RateLimitPolicies.ResolveIpKey(httpContext),
                permitLimit: 10,
                window: TimeSpan.FromMinutes(1)));
});

var app = builder.Build();

// X-RateLimit-Remaining headers must register their Response.OnStarting callback
// BEFORE any middleware that writes a response (including JwtValidationMiddleware's
// 401) — OnStarting fires exactly once, right before headers commit, on all response
// paths alike. So RateLimitHeaders runs first.
app.UseRateLimitHeaders();

// Middleware Chain: JWT validation runs after rate-limit headers are armed, so that
// 401 responses inherit the X-RateLimit-Remaining header. It still runs before
// UseRateLimiter() itself, for the reason noted below.
app.UseJwtValidation();

// Must run before UseRateLimiter(): the OTP policy's partition key (phone number)
// is read out of the request body here and cached on HttpContext.Items, because
// the rate limiter's partitioner callback is synchronous and can't itself await
// a body read. See OtpPhoneNumberBufferingMiddleware for the buffering/rewind
// details that keep the body intact for YARP to forward downstream afterward.
app.UseOtpPhoneNumberBuffering();

app.UseRateLimiter();

// Config-driven proxying: every request either matches one of the 8 routes in
// appsettings.json and gets forwarded to its cluster, or matches nothing and
// falls through to ASP.NET Core's default 404 — no MapFallback/catch-all is
// registered here on purpose, so unmatched routes aren't swallowed before
// YARP (or the framework) gets to return that 404.
app.MapReverseProxy();

app.Run();
