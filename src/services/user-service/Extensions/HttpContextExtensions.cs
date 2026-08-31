namespace user_service.Extensions;

// Centralizes identity-header extraction (JWT Identity rule: read
// X-User-Id/Role/Gender from headers only, never decode a JWT inside a
// service) in one place, usable from any controller action via
// HttpContext.GetUserId() / TryGetUserId() — same shape the
// dotnet-mvc-controllers skill file documents.
public static class HttpContextExtensions
{
    // TryGetUserId, not a throwing GetUserId, is what OtpController uses:
    // this lets the controller return a proper { code, message, traceId }
    // error envelope (project rule 6) on a missing/malformed header instead
    // of an unhandled exception producing a bare 500. In normal operation
    // this header is always present — api-gateway's JwtValidationMiddleware
    // only reaches user-service's /auth/otp/* routes after validating a JWT
    // (see that middleware's PublicRoutePaths comment) — this is defense in
    // depth for a misconfigured environment where user-service is reachable
    // directly, bypassing the gateway.
    public static bool TryGetUserId(this HttpContext context, out long userId) =>
        long.TryParse(context.Request.Headers["X-User-Id"].ToString(), out userId);
}
