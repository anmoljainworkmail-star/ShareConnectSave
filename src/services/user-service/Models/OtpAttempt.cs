namespace user_service.Models;

// Rate-limiting state modeled as a row, not an in-memory counter. Storing
// attempt_count/locked_until in the same DB as the rest of user data means a
// pod restart or horizontal scale-out of User Service doesn't reset an
// attacker's lockout — the constraint survives process lifetime.
public class OtpAttempt
{
    public long Id { get; set; }

    public string Phone { get; set; } = string.Empty;

    public int AttemptCount { get; set; }

    public DateTime? LockedUntil { get; set; }
}
