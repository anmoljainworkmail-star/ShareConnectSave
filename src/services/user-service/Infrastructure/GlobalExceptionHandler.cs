using Microsoft.AspNetCore.Diagnostics;
using user_service.Contracts;

namespace user_service.Infrastructure;

// Global Exception Handling (fix): every controller action already returns
// the project's {code, message, traceId} error envelope explicitly for
// EXPECTED failures (bad input, wrong OTP, lockout). IExceptionHandler is the
// catch-all for UNEXPECTED ones — an unhandled exception anywhere in the
// pipeline. Without this, ASP.NET Core's built-in AddProblemDetails()/
// UseExceptionHandler() combo writes RFC 7807 ProblemDetails JSON
// ({type, title, status, detail, instance}), a completely different shape
// with no "message" key at all — a client written against the
// {code, message, traceId} envelope (this service's OWN OtpController error
// paths, plus every other ShareConnectSave service) would have to special-case
// the one error path it can't predict in advance. Implementing IExceptionHandler
// directly and writing ErrorResponse ourselves means literally every error
// path — expected and unexpected — produces byte-for-byte the same shape.
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

        var errorResponse = new ErrorResponse(
            "INTERNAL_ERROR",
            "An unexpected error occurred.",
            httpContext.TraceIdentifier);

        await httpContext.Response.WriteAsJsonAsync(errorResponse, cancellationToken);

        // Returning true tells the exception-handling middleware this
        // exception has been fully handled — no further handler (and no
        // ProblemDetails fallback) should also try to write a response.
        return true;
    }
}
