namespace chat_service.Extensions;

// Centralizes identity-header extraction (JWT Identity rule: read
// X-User-Id/Role/Gender from headers only, never decode a JWT inside a
// service) in one place - copied from user-service's identical helper (see
// that file's comment for the full reasoning). ChatController uses this via
// HttpContext.TryGetUserId(); ChatHub reuses the SAME extension method
// against Context.GetHttpContext() (SignalR's HTTP context is the same
// handshake request the gateway already injected X-User-Id into), so there
// is exactly one place in this service that knows how to read identity,
// regardless of whether the caller arrived over plain HTTP or a SignalR
// connection.
public static class HttpContextExtensions
{
    public static bool TryGetUserId(this HttpContext context, out long userId) =>
        long.TryParse(context.Request.Headers["X-User-Id"].ToString(), out userId);
}
