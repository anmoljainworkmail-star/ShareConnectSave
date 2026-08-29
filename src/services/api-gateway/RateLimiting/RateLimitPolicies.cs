namespace api_gateway.RateLimiting;

// Partitioned Rate Limiting: "rate limit" is not one global counter — it is a family
// of counters, each keyed to whichever dimension actually represents abuse risk for
// that endpoint (an IP for broad traffic shaping, a user id for per-account throttling,
// a phone number for an SMS-cost-bearing endpoint). This class is the single place that
// names the three policies and knows how to compute each one's partition key, so
// Program.cs and the header middleware never have to re-derive that logic separately.
public static class RateLimitPolicies
{
    public const string GlobalPolicy = "GlobalPolicy";
    public const string ConnectionRequestPolicy = "ConnectionRequestPolicy";
    public const string OtpSendPolicy = "OtpSendPolicy";

    // Shared with RateLimitHeadersMiddleware, which must eagerly touch this exact
    // partition (same policy name, same window shape) before JwtValidationMiddleware
    // runs — otherwise a 401 short-circuit on an IP's first-ever request never creates
    // this cache entry, and X-RateLimit-Remaining silently goes missing on that 401.
    // Named constants (not literals duplicated at each call site) keep the two in sync.
    public const int GlobalPolicyPermitLimit = 100;
    public static readonly TimeSpan GlobalPolicyWindow = TimeSpan.FromMinutes(1);
    public const int GlobalPolicySegmentsPerWindow = 4;

    // Follow-up caught during T013 review: /user/auth/google is a public route
    // (see JwtValidationMiddleware.PublicRoutePaths — same chicken-and-egg reason
    // OTP is public) but, unlike OTP, it had no named policy of its own, only
    // GlobalPolicy's 100/min-per-IP. Each hit here costs user-service a Google
    // token verification plus a fresh JWT mint, so a login flood is a JWT-minting
    // flood, not just ordinary traffic — the same abuse shape OTP already guards
    // against, just without an SMS bill attached.
    public const string GoogleAuthPolicy = "GoogleAuthPolicy";

    // Path correction (see T013 ticket note, discovered while implementing T012):
    // these are the gateway-facing paths — YARP's route prefix plus the downstream
    // service's own OpenAPI path — never the un-prefixed, service-local path. A
    // policy bound to the wrong path silently never matches any real request, the
    // same failure mode that locked out login during T012 until it was caught.
    public const string ConnectionRequestPath = "/connection/connections";
    public const string OtpSendPath = "/user/auth/otp/send";
    public const string GoogleAuthPath = "/user/auth/google";

    // Key used to hand the OTP phone number from OtpPhoneNumberBufferingMiddleware
    // (which reads it out of the raw request body before UseRateLimiter() runs)
    // through to this class's synchronous partition-key resolver.
    public const string OtpPhoneNumberItemKey = "RateLimiting.OtpPhoneNumber";

    public static bool IsConnectionRequestRoute(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) &&
        string.Equals(request.Path.Value, ConnectionRequestPath, StringComparison.OrdinalIgnoreCase);

    public static bool IsOtpSendRoute(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) &&
        string.Equals(request.Path.Value, OtpSendPath, StringComparison.OrdinalIgnoreCase);

    public static bool IsGoogleAuthRoute(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) &&
        string.Equals(request.Path.Value, GoogleAuthPath, StringComparison.OrdinalIgnoreCase);

    // What NOT to do (per ticket): this gateway has no ForwardedHeaders middleware
    // configured (see T012's Program.cs), so trusting X-Forwarded-For here would let
    // any client spoof its own partition key just by setting that header. Only the
    // connection's own observed remote address is trustworthy.
    public static string ResolveIpKey(HttpContext context) =>
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";

    public static string ResolveUserIdKey(HttpContext context)
    {
        var userId = context.Request.Headers["X-User-Id"].ToString();
        return string.IsNullOrWhiteSpace(userId) ? "unknown-user" : userId;
    }

    public static string ResolvePhoneKey(HttpContext context)
    {
        if (context.Items.TryGetValue(OtpPhoneNumberItemKey, out var value) &&
            value is string phoneNumber &&
            !string.IsNullOrWhiteSpace(phoneNumber))
        {
            return phoneNumber;
        }

        // Fail closed on abuse, not on availability: a request whose body couldn't be
        // parsed still gets partitioned — by IP instead of phone number — rather than
        // slipping through with no OTP-specific throttling at all. Validating the body
        // shape itself is user-service's job (400), not this middleware's.
        return $"malformed-body:{ResolveIpKey(context)}";
    }
}
