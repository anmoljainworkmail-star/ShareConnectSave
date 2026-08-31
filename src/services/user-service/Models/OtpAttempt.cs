namespace user_service.Models;

// Rate-limiting state modeled as a row, not an in-memory counter. Storing
// attempt_count/locked_until in the same DB as the rest of user data means a
// pod restart or horizontal scale-out of User Service doesn't reset an
// attacker's lockout — the constraint survives process lifetime.
//
// T017 extends this table beyond pure lockout bookkeeping to also hold the
// currently outstanding OTP code (hashed) and its lifetime — one row per
// phone (see the unique index on Phone in AppDbContext) answers both "is
// this phone currently locked out" and "what code, if any, is still valid
// for this phone" without a second table.
public class OtpAttempt
{
    public long Id { get; set; }

    public string Phone { get; set; } = string.Empty;

    public int AttemptCount { get; set; }

    public DateTime? LockedUntil { get; set; }

    // T017: the failed-attempt window is a separate anchor from LockedUntil.
    // Brute-force Protection pattern — "5 failures within 10 minutes" needs
    // its own start-of-window timestamp; without one, a 6th failure arriving
    // days after the 5th would still look like the same streak and lock the
    // phone again immediately, instead of starting a fresh 10-minute window.
    public DateTime? WindowStartedAt { get; set; }

    // Hash, never plaintext — ticket-explicit requirement. This column only
    // ever answers "does the code the caller typed match what we sent",
    // nothing more; a DB leak (backup, misconfigured access, insider) can
    // never hand an attacker a directly usable code.
    public string? CodeHash { get; set; }

    // Idempotency anchor (T017's headline pattern): OtpService.SendOtpAsync
    // checks CodeExpiresAt against "now" to decide whether a resend is a
    // true retry of an outstanding code (no-op, same code still valid) or a
    // request for a brand-new one.
    public DateTime? CodeCreatedAt { get; set; }

    public DateTime? CodeExpiresAt { get; set; }

    // Optimistic Concurrency Token: two concurrent wrong-code guesses (or a
    // send racing a verify) can both read this row before either writes it
    // back. Without a version stamp, the second UPDATE silently overwrites
    // the first's increment instead of building on it — a classic lost
    // update that would let an attacker's guesses evade the 5-attempt
    // lockout. SQL Server's `rowversion` type auto-increments on every write
    // to this row; EF Core includes it in the generated UPDATE's WHERE
    // clause, so a stale write throws DbUpdateConcurrencyException instead
    // of succeeding silently (see AppDbContext's IsRowVersion() config and
    // OtpService's catch/retry around it).
    public byte[]? RowVersion { get; set; }
}
