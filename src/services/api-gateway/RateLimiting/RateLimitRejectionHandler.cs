using System.Globalization;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;

namespace api_gateway.RateLimiting;

// Shared Error Envelope / Consistent API Contract: every non-2xx response in this
// platform — a 401 from JwtValidationMiddleware, a ProblemDetails response from any
// downstream service, and now a 429 from here — comes back to the Angular client in
// exactly the same { code, message, traceId } shape, so the client parses errors one
// way regardless of which limiter or service produced them. This mirrors the local
// anonymous-envelope pattern JwtValidationMiddleware.WriteUnauthorizedAsync already
// established in T012 rather than inventing a second, differently-shaped error body.
public static class RateLimitRejectionHandler
{
    public static async ValueTask HandleAsync(OnRejectedContext context, CancellationToken cancellationToken)
    {
        context.HttpContext.Response.StatusCode = StatusCodes.Status429TooManyRequests;
        context.HttpContext.Response.ContentType = "application/json";

        // RetryAfter metadata: only the fixed-window (and token-bucket) style
        // limiters populate this — the sliding-window global policy never does —
        // so this is best-effort, not a guaranteed header on every 429.
        if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
        {
            context.HttpContext.Response.Headers.RetryAfter =
                ((int)retryAfter.TotalSeconds).ToString(CultureInfo.InvariantCulture);
        }

        var errorEnvelope = new
        {
            code = "RATE_LIMIT_EXCEEDED",
            message = "Too many requests. Please slow down and try again shortly.",
            traceId = context.HttpContext.TraceIdentifier,
        };

        await context.HttpContext.Response.WriteAsJsonAsync(errorEnvelope, cancellationToken);
    }
}
