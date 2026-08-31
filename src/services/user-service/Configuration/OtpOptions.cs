namespace user_service.Configuration;

// Dependency Inversion (SOLID D), same shape as JwtIssuerOptions: OtpService
// depends on IOptions<OtpOptions>, never reads configuration keys ad hoc.
// Every value has a sensible default (bound from appsettings.json's "Otp"
// section) so local dev works with zero config, but every value is also
// overridable via the standard ASP.NET Core double-underscore env var
// convention (e.g. Otp__LockoutMinutes) — the ticket explicitly calls out
// "no static lockout duration" as a thing to avoid.
public class OtpOptions
{
    public const string SectionName = "Otp";

    // How long a generated code stays valid for VerifyOtpAsync. Deliberately
    // NOT the same window SendOtpAsync uses to decide "retry vs resend" — see
    // ResendCooldownSeconds.
    public int CodeExpiryMinutes { get; set; } = 5;

    // How long after sending a code that a repeat POST /auth/otp/send is
    // treated as an accidental duplicate (a double-tap, a client retrying a
    // slow/dropped response) rather than a genuine "I never got it, send me
    // a new one" request. Short on purpose: long enough to absorb a network
    // retry, far short of CodeExpiryMinutes, so a user who really did lose
    // the text isn't stuck waiting out the full validity window before they
    // can ask for another one. Past this cooldown, SendOtpAsync generates
    // and sends a fresh code, invalidating the old one.
    public int ResendCooldownSeconds { get; set; } = 30;

    // Brute-force protection threshold/window: this many wrong attempts
    // inside this many minutes triggers the lockout below.
    public int MaxAttempts { get; set; } = 5;

    public int AttemptWindowMinutes { get; set; } = 10;

    public int LockoutMinutes { get; set; } = 15;
}
