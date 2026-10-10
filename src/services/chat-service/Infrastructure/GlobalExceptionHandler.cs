using Microsoft.AspNetCore.Diagnostics;
using chat_service.Contracts;

namespace chat_service.Infrastructure;

// Global Exception Handling - copied from user-service's identical class
// (see that file's comment for the full reasoning: IExceptionHandler, not
// AddProblemDetails' RFC 7807 shape, is what lets this handler emit the same
// { code, message, traceId } envelope every expected error path in
// ChatController already returns explicitly). This service had no HTTP
// surface before T036 (T035 was Mongo setup only), so there was nothing for
// an unhandled exception to happen IN until this ticket added
// ChatController/ChatHub.
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

        return true;
    }
}
