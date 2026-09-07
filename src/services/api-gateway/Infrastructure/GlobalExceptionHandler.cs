using Microsoft.AspNetCore.Diagnostics;

namespace api_gateway.Infrastructure;

// Global Exception Handling (fix, found during integration testing — see
// user-service/Infrastructure/GlobalExceptionHandler.cs, T017, for the
// original of this pattern): every expected error path in this gateway
// already returns the project's { code, message, traceId } envelope
// explicitly — JwtValidationMiddleware's 401s, RateLimitRejectionHandler's
// 429s. This is the catch-all for an UNEXPECTED one — any unhandled
// exception anywhere in the pipeline (the rate-limiter ObjectDisposedException
// this ticket-adjacent fix was found alongside is exactly the kind of thing
// this exists to stop leaking as a raw stack trace). Without this, ASP.NET
// Core's default behavior in Development is the built-in exception page —
// full stack trace, straight to the caller — which is a much worse problem
// on a public-facing API gateway than on any one downstream service.
public class GlobalExceptionHandler : IExceptionHandler
{
    private readonly ILogger<GlobalExceptionHandler> _logger;

    public GlobalExceptionHandler(ILogger<GlobalExceptionHandler> logger)
    {
        _logger = logger;
    }

    public async ValueTask<bool> TryHandleAsync(
        HttpContext httpContext,
        Exception exception,
        CancellationToken cancellationToken)
    {
        _logger.LogError(exception, "Unhandled exception while processing {Method} {Path}",
            httpContext.Request.Method, httpContext.Request.Path);

        httpContext.Response.StatusCode = StatusCodes.Status500InternalServerError;
        httpContext.Response.ContentType = "application/json";

        // Local anonymous envelope — same shape/convention as
        // JwtValidationMiddleware.WriteUnauthorizedAsync and
        // RateLimitRejectionHandler already use in this project (no shared
        // ErrorResponse type exists here; user-service's is a separate
        // service's internal type, not referenced across the process boundary).
        var errorEnvelope = new
        {
            code = "INTERNAL_ERROR",
            message = "An unexpected error occurred.",
            traceId = httpContext.TraceIdentifier,
        };

        await httpContext.Response.WriteAsJsonAsync(errorEnvelope, cancellationToken);

        // Returning true tells the exception-handling middleware this
        // exception has been fully handled — no further handler (and no
        // ProblemDetails fallback) should also try to write a response.
        return true;
    }
}
