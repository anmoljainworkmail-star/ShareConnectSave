using System.Text.Json;

namespace api_gateway.RateLimiting;

// Buffered Body Read: the OTP policy partitions by phone number, but rate limiting
// middleware runs before any endpoint/model binding — and this gateway never model-binds
// at all, it is a pure YARP proxy that forwards raw bytes downstream. So the phone number
// has to be pulled out of the raw request body by hand, here, before UseRateLimiter() runs.
// EnableBuffering() plus rewinding Body.Position back to 0 afterward is what lets YARP still
// forward the exact same bytes to user-service once this middleware is done reading them —
// without the rewind, user-service would receive an already-consumed, empty body.
public class OtpPhoneNumberBufferingMiddleware
{
    private readonly RequestDelegate _next;

    public OtpPhoneNumberBufferingMiddleware(RequestDelegate next)
    {
        _next = next;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        // Guard clause: only the one endpoint this policy applies to pays the cost of
        // buffering its body — every other proxied request is untouched.
        if (!RateLimitPolicies.IsOtpSendRoute(context.Request))
        {
            await _next(context);
            return;
        }

        context.Request.EnableBuffering();

        try
        {
            using var document = await JsonDocument.ParseAsync(context.Request.Body, cancellationToken: context.RequestAborted);
            if (document.RootElement.TryGetProperty("phone_number", out var phoneNumberElement) &&
                phoneNumberElement.ValueKind == JsonValueKind.String)
            {
                context.Items[RateLimitPolicies.OtpPhoneNumberItemKey] = phoneNumberElement.GetString();
            }
        }
        catch (JsonException)
        {
            // Fail open here, not closed: a malformed body is user-service's 400 to
            // return, not this middleware's job to reject. RateLimitPolicies.ResolvePhoneKey()
            // falls back to an IP-based key when nothing was cached above, so the request
            // still gets *some* partition instead of skipping OTP rate limiting entirely.
        }
        finally
        {
            // Rewind: the body stream must look completely unread to YARP, or
            // user-service receives zero bytes and every OTP request 400s.
            context.Request.Body.Position = 0;
        }

        await _next(context);
    }
}

public static class OtpPhoneNumberBufferingMiddlewareExtensions
{
    public static IApplicationBuilder UseOtpPhoneNumberBuffering(this IApplicationBuilder app) =>
        app.UseMiddleware<OtpPhoneNumberBufferingMiddleware>();
}
