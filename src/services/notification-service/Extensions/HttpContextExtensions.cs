namespace notification_service.Extensions;

// Centralizes identity-header extraction (JWT Identity rule: read
// X-User-Id/Role/Gender from headers only, never decode a JWT inside a
// service) in one place - ported verbatim from chat-service's identical
// helper (see that file's comment for the full reasoning), per this
// ticket's instruction to reuse the same TryGetUserId shape rather than
// inventing a new one. NotificationHub reuses this against
// Context.GetHttpContext() the same way ChatHub does - SignalR's HTTP
// context is the same handshake request the gateway already injected
// X-User-Id into, so this stays the one place in this service that knows
// how to read identity, regardless of transport.
public static class HttpContextExtensions
{
    public static bool TryGetUserId(this HttpContext context, out long userId) =>
        long.TryParse(context.Request.Headers["X-User-Id"].ToString(), out userId);
}
