using System.Globalization;

namespace api_gateway.RateLimiting;

// .NET's built-in rate limiter does not add X-RateLimit-Remaining automatically (see
// RateLimitPartitionRegistry for why there's no public API to read it after the fact
// otherwise) — this middleware adds it itself. It must be registered BEFORE
// UseRateLimiter() in Program.cs so that Response.OnStarting is armed before the
// limiter (or its OnRejected handler) ever writes to the response: OnStarting fires
// exactly once, right before the first byte goes out, on both the success path and the
// 429 path alike, which is what makes "present on every response" true.
public class RateLimitHeadersMiddleware
{
    private readonly RequestDelegate _next;
    private readonly IRateLimitPartitionRegistry _registry;

    public RateLimitHeadersMiddleware(RequestDelegate next, IRateLimitPartitionRegistry registry)
    {
        _next = next;
        _registry = registry;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        // Fix for T013 review (final blocker): this middleware runs before
        // JwtValidationMiddleware, which can short-circuit with a 401 and never
        // reach UseRateLimiter() at all. GetSlidingWindowPartition is the only
        // code that creates an IP's GlobalPolicy cache entry — if it never runs,
        // ResolveRemaining's later lookup misses and the header goes missing on
        // that 401. Touching (and discarding) the partition here guarantees the
        // entry exists — same policy name/window as Program.cs's GlobalLimiter,
        // via the shared constants on RateLimitPolicies — regardless of whether
        // a downstream middleware ever lets the request reach the real limiter.
        _registry.GetSlidingWindowPartition(
            RateLimitPolicies.GlobalPolicy,
            RateLimitPolicies.ResolveIpKey(context),
            permitLimit: RateLimitPolicies.GlobalPolicyPermitLimit,
            window: RateLimitPolicies.GlobalPolicyWindow,
            segmentsPerWindow: RateLimitPolicies.GlobalPolicySegmentsPerWindow);

        context.Response.OnStarting(() =>
        {
            var remaining = ResolveRemaining(context);
            if (remaining.HasValue)
            {
                context.Response.Headers["X-RateLimit-Remaining"] = remaining.Value.ToString(CultureInfo.InvariantCulture);
            }

            return Task.CompletedTask;
        });

        await _next(context);
    }

    // Partitioned Rate Limiting: a single request can be governed by more than one
    // policy at once — the global IP policy always, plus a route-specific policy on
    // the two narrow endpoints. Reporting the smallest remaining count across every
    // policy that actually applies is the honest answer to "how many more requests
    // can this client safely make" — whichever limit is closest to zero is the one
    // that will reject the client's very next request.
    private long? ResolveRemaining(HttpContext context)
    {
        long? remaining = _registry.GetRemainingPermits(
            RateLimitPolicies.GlobalPolicy, RateLimitPolicies.ResolveIpKey(context));

        if (RateLimitPolicies.IsConnectionRequestRoute(context.Request))
        {
            var connectionRemaining = _registry.GetRemainingPermits(
                RateLimitPolicies.ConnectionRequestPolicy, RateLimitPolicies.ResolveUserIdKey(context));
            remaining = Min(remaining, connectionRemaining);
        }

        if (RateLimitPolicies.IsOtpSendRoute(context.Request))
        {
            var otpRemaining = _registry.GetRemainingPermits(
                RateLimitPolicies.OtpSendPolicy, RateLimitPolicies.ResolvePhoneKey(context));
            remaining = Min(remaining, otpRemaining);
        }

        if (RateLimitPolicies.IsGoogleAuthRoute(context.Request))
        {
            var googleAuthRemaining = _registry.GetRemainingPermits(
                RateLimitPolicies.GoogleAuthPolicy, RateLimitPolicies.ResolveIpKey(context));
            remaining = Min(remaining, googleAuthRemaining);
        }

        return remaining;
    }

    private static long? Min(long? a, long? b)
    {
        if (!a.HasValue)
        {
            return b;
        }

        if (!b.HasValue)
        {
            return a;
        }

        return Math.Min(a.Value, b.Value);
    }
}

public static class RateLimitHeadersMiddlewareExtensions
{
    public static IApplicationBuilder UseRateLimitHeaders(this IApplicationBuilder app) =>
        app.UseMiddleware<RateLimitHeadersMiddleware>();
}
