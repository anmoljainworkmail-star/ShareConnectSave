namespace user_service.Services;

using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Models;
using user_service.Repositories.Interfaces;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S): this class owns the OTP business rules —
// code generation, idempotent resend, brute-force lockout accounting, and
// the "when does status become active" decision. It never touches
// AppDbContext, HttpClient, or Twilio's URL shape directly — those live
// behind IOtpRepository/IUserRepository/ITwilioClient (Dependency Inversion,
// SOLID D), which is what a unit test mocks instead of standing up SQL
// Server plus a real Twilio account per test.
public class OtpService : IOtpService
{
    private readonly IOtpRepository _otpRepository;
    private readonly IUserRepository _userRepository;
    private readonly ITwilioClient _twilioClient;
    private readonly OtpOptions _options;
    private readonly ILogger<OtpService> _logger;

    public OtpService(
        IOtpRepository otpRepository,
        IUserRepository userRepository,
        ITwilioClient twilioClient,
        IOptions<OtpOptions> options,
        ILogger<OtpService> logger)
    {
        _otpRepository = otpRepository;
        _userRepository = userRepository;
        _twilioClient = twilioClient;
        _options = options.Value;
        _logger = logger;
    }

    public async Task SendOtpAsync(string phoneNumber, CancellationToken cancellationToken = default)
    {
        var now = DateTime.UtcNow;
        var attempt = await _otpRepository.GetByPhoneAsync(phoneNumber);

        // Idempotency (T017's headline pattern) vs. a genuine resend: these
        // are two different requests that look identical on the wire, so
        // they need two different windows, not one. A request arriving
        // milliseconds after the last one (slow network, an impatient
        // double-tap) is almost certainly a RETRY of a call that already
        // succeeded — the correct idempotent response is "do nothing, the
        // code was already texted", not "generate and send a second one",
        // which is also what stops a spam-tap from burning five Twilio
        // sends. But a request arriving well after that — because the text
        // never arrived, or the user typo'd their number the first time and
        // corrected it — is a genuine "send me a new one", and must not be
        // forced to wait out the full CodeExpiryMinutes just because the
        // previous code hasn't technically expired yet. ResendCooldownSeconds
        // is that short "still probably the same request" window;
        // CodeExpiryMinutes (checked separately in VerifyOtpAsync) stays the
        // much longer "how long can this code still be used" window — only
        // the cooldown is short-circuited here, so a phone can never be
        // resent-into-a-lockout via SendOtpAsync itself.
        if (attempt is not null && attempt.CodeHash is not null &&
            attempt.CodeCreatedAt is { } lastSentAt &&
            lastSentAt.AddSeconds(_options.ResendCooldownSeconds) > now)
        {
            _logger.LogInformation("OTP send is an idempotent no-op — a code was already sent within the resend cooldown window.");
            return;
        }

        var code = GenerateCode();
        var expiresAt = now.AddMinutes(_options.CodeExpiryMinutes);

        if (attempt is null)
        {
            attempt = new OtpAttempt
            {
                Phone = phoneNumber,
                CodeHash = HashCode(code),
                CodeCreatedAt = now,
                CodeExpiresAt = expiresAt,
            };

            try
            {
                await _otpRepository.AddAsync(attempt);
            }
            catch (DbUpdateException)
            {
                // Unique-index race (fix): two concurrent first-time sends
                // for the same brand-new phone number both read "no row
                // exists" and both attempt an INSERT — only one can win
                // against the unique index on Phone (AppDbContext.cs), the
                // other throws DbUpdateException instead of returning a
                // usable result. The loser here didn't lose the race for a
                // bad reason (a real duplicate) — it lost because it was
                // slightly slower, so the correct recovery is to re-fetch
                // the row the winner just created and apply this send's
                // code to it, not to let the exception propagate as a 500.
                attempt = await _otpRepository.GetByPhoneAsync(phoneNumber)
                    ?? throw new InvalidOperationException(
                        $"otp_attempts row for phone was not found immediately after a unique-index insert conflict.");
                attempt.CodeHash = HashCode(code);
                attempt.CodeCreatedAt = now;
                attempt.CodeExpiresAt = expiresAt;
                await _otpRepository.UpdateAsync(attempt);
            }
        }
        else
        {
            attempt.CodeHash = HashCode(code);
            attempt.CodeCreatedAt = now;
            attempt.CodeExpiresAt = expiresAt;

            try
            {
                await _otpRepository.UpdateAsync(attempt);
            }
            catch (DbUpdateConcurrencyException)
            {
                // Optimistic Concurrency retry (fix, see OtpAttempt.RowVersion):
                // this row changed underneath us between the read above and
                // this write (e.g. a failed-verify attempt updated it in
                // between) — re-fetch the current row and reapply this
                // send's fields to it rather than losing the race silently.
                var fresh = await _otpRepository.GetByPhoneAsync(phoneNumber)
                    ?? throw new InvalidOperationException(
                        $"otp_attempts row for phone disappeared during a concurrency retry.");
                fresh.CodeHash = HashCode(code);
                fresh.CodeCreatedAt = now;
                fresh.CodeExpiresAt = expiresAt;
                await _otpRepository.UpdateAsync(fresh);
            }
        }

        await _twilioClient.SendSmsAsync(
            phoneNumber,
            $"Your ShareConnectSave verification code is {code}. It expires in {_options.CodeExpiryMinutes} minutes.",
            cancellationToken);
    }

    public async Task<OtpVerificationOutcome> VerifyOtpAsync(
        long userId,
        string phoneNumber,
        string code,
        CancellationToken cancellationToken = default)
    {
        var now = DateTime.UtcNow;
        var attempt = await _otpRepository.GetByPhoneAsync(phoneNumber);

        // Guard Clause (project convention): a locked phone is rejected
        // before any code comparison happens at all — even a CORRECT code
        // must not succeed while locked, or the lockout (Brute-force
        // Protection pattern) is meaningless.
        if (attempt?.LockedUntil is { } lockedUntil && lockedUntil > now)
        {
            return new OtpVerificationOutcome(OtpVerificationResult.Locked, lockedUntil, null);
        }

        var codeIsValid =
            attempt is not null &&
            attempt.CodeHash is not null &&
            attempt.CodeExpiresAt is { } expiresAt &&
            expiresAt > now &&
            FixedTimeEquals(attempt.CodeHash, HashCode(code));

        // Guard Clause: wrong/expired/never-requested code is recorded as a
        // failed attempt and rejected before ever touching the users table.
        if (!codeIsValid)
        {
            var updatedAttempt = await RecordFailedAttemptAsync(attempt, phoneNumber, now);

            // Fix (AC3): if THIS failure is the one that just crossed
            // MaxAttempts, RecordFailedAttemptAsync already set LockedUntil
            // on the row above — surface Locked (429/OTP_LOCKED with the
            // lockout time) on this very response instead of InvalidCode.
            // Without this check, AC3 ("after 5 failures → 429... lockout
            // time in response") would only be true starting on a 6th call,
            // since the 5th failure's own response would still say 400.
            if (updatedAttempt.LockedUntil is { } justLockedUntil && justLockedUntil > now)
            {
                return new OtpVerificationOutcome(OtpVerificationResult.Locked, justLockedUntil, null);
            }

            return new OtpVerificationOutcome(OtpVerificationResult.InvalidCode, null, null);
        }

        // codeIsValid can only be true above if attempt is not null — the
        // null-forgiving operator here just tells the compiler what that
        // boolean already proved.
        var verifiedAttempt = attempt!;

        var user = await _userRepository.GetByIdAsync(userId);
        if (user is null)
        {
            // The caller is authenticated (JWT Identity rule: X-User-Id came
            // from a gateway-validated token), so a missing user row here
            // means the account was deleted mid-session, not a bad request —
            // an exception, not a 4xx, is the correct signal.
            throw new InvalidOperationException($"User {userId} not found during OTP verification.");
        }

        // Fast-path (fix): a phone number identifies one real person — once
        // verified on one account it must never also verify on a second,
        // different account (e.g. two Google sign-ins racing to claim the
        // same number). This SELECT-then-check catches the common case
        // before the code is consumed or the phone is assigned, so a genuine
        // conflict doesn't burn the caller's correctly-entered code for
        // nothing — they can still request a fresh one for a phone number
        // that is actually theirs. It is only an optimization, though: two
        // concurrent verifications can both pass this check before either
        // commits. AppDbContext's filtered unique index on Users.Phone
        // (HasFilter("[PhoneVerifiedAt] IS NOT NULL")) is the real guarantee —
        // enforced below when the user row is actually saved.
        var conflictingUser = await _userRepository.GetByVerifiedPhoneAsync(phoneNumber);
        if (conflictingUser is not null && conflictingUser.Id != user.Id)
        {
            return new OtpVerificationOutcome(OtpVerificationResult.PhoneAlreadyInUse, null, null);
        }

        // Success: reset lockout/attempt state and consume the code — a
        // code is single-use, verifying twice with the same code must not
        // succeed the second time.
        verifiedAttempt.AttemptCount = 0;
        verifiedAttempt.WindowStartedAt = null;
        verifiedAttempt.LockedUntil = null;
        verifiedAttempt.CodeHash = null;
        verifiedAttempt.CodeCreatedAt = null;
        verifiedAttempt.CodeExpiresAt = null;
        await _otpRepository.UpdateAsync(verifiedAttempt);

        user.Phone = phoneNumber;
        user.PhoneVerifiedAt = now;

        // Saga Compensation (T017's "Patterns demonstrated"): status only
        // advances past its current value once EVERY UserOnboardingSaga
        // condition is true (see .claude/skills/saga.md). Phone verification
        // succeeding is necessary but not sufficient — if the profile isn't
        // complete yet, status is left untouched here; T019's identity
        // verification will add a third condition the same way, chaining
        // conditions across async steps instead of any single step deciding
        // the outcome alone.
        if (user.IsProfileComplete())
        {
            user.Status = "active";
        }

        try
        {
            await _userRepository.UpdateAsync(user);
        }
        catch (DbUpdateException)
        {
            // Unique-index race (fix): the fast-path check above did not
            // catch this — a second, concurrent verification of the same
            // phone number on a different account committed first. The
            // database's filtered unique index on Users.Phone
            // (PhoneVerifiedAt IS NOT NULL) is what actually enforces "at
            // most one verified account per phone", so a loss here is a
            // real conflict, not a bug — surface it exactly the same way
            // the fast-path check would have.
            return new OtpVerificationOutcome(OtpVerificationResult.PhoneAlreadyInUse, null, null);
        }

        return new OtpVerificationOutcome(OtpVerificationResult.Verified, null, user.Status);
    }

    // Returns the persisted attempt row (with AttemptCount/LockedUntil as
    // they stand AFTER this failure was recorded) so VerifyOtpAsync can tell
    // whether this exact call is the one that crossed MaxAttempts.
    private async Task<OtpAttempt> RecordFailedAttemptAsync(OtpAttempt? attempt, string phoneNumber, DateTime now)
    {
        var isNew = attempt is null;
        attempt ??= new OtpAttempt { Phone = phoneNumber };

        ApplyFailedAttempt(attempt, now);

        if (isNew)
        {
            try
            {
                await _otpRepository.AddAsync(attempt);
                return attempt;
            }
            catch (DbUpdateException)
            {
                // Unique-index race (fix, mirrors SendOtpAsync's isNew
                // branch above): two concurrent verify calls against a
                // phone with no prior /send both read "no row exists" and
                // both attempt an INSERT — only one can win against the
                // unique index on Phone (AppDbContext.cs). The loser must
                // re-fetch the row the winner just created and apply THIS
                // failure on top of it, not let the exception surface as an
                // unhandled 500.
                var fresh = await _otpRepository.GetByPhoneAsync(phoneNumber)
                    ?? throw new InvalidOperationException(
                        $"otp_attempts row for phone was not found immediately after a unique-index insert conflict.");
                ApplyFailedAttempt(fresh, now);
                await _otpRepository.UpdateAsync(fresh);
                return fresh;
            }
        }

        try
        {
            await _otpRepository.UpdateAsync(attempt);
            return attempt;
        }
        catch (DbUpdateConcurrencyException)
        {
            // Optimistic Concurrency retry (fix, see OtpAttempt.RowVersion's
            // class comment): two concurrent wrong-code guesses can both
            // read the same AttemptCount before either writes back — without
            // a version check, the second UPDATE would silently overwrite
            // the first's increment (a lost update) instead of building on
            // it, letting an attacker's guesses evade the 5-attempt lockout.
            // EF Core detects the stale write and throws here instead of
            // applying it; re-fetching the row THAT WON and reapplying this
            // failure's rule against its current values is what makes every
            // failed guess count, even under concurrency.
            var fresh = await _otpRepository.GetByPhoneAsync(phoneNumber)
                ?? throw new InvalidOperationException(
                    $"otp_attempts row for phone disappeared during a concurrency retry.");
            ApplyFailedAttempt(fresh, now);
            await _otpRepository.UpdateAsync(fresh);
            return fresh;
        }
    }

    // Brute-force Protection (T017's headline pattern): the failure count is
    // scoped to a rolling window, not a lifetime total. An attacker who
    // fails 4 times, waits past the window, and tries again should NOT be
    // one attempt away from a lockout — that is a fresh window. Without this
    // reset, "5 failures in 10 minutes" (a real signal of active guessing)
    // degrades into "5 failures ever" (noise accumulated over weeks of
    // normal typos).
    private void ApplyFailedAttempt(OtpAttempt attempt, DateTime now)
    {
        var windowExpired =
            attempt.WindowStartedAt is null ||
            now > attempt.WindowStartedAt.Value.AddMinutes(_options.AttemptWindowMinutes);

        if (windowExpired)
        {
            attempt.WindowStartedAt = now;
            attempt.AttemptCount = 1;
        }
        else
        {
            attempt.AttemptCount += 1;
        }

        if (attempt.AttemptCount >= _options.MaxAttempts)
        {
            attempt.LockedUntil = now.AddMinutes(_options.LockoutMinutes);
        }
    }

    private static string GenerateCode() => Random.Shared.Next(0, 1_000_000).ToString("D6");

    private static string HashCode(string code) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(code)));

    // Constant-time comparison: a naive == / string.Equals on a secret hash
    // leaks timing information proportional to how many leading bytes
    // matched — a narrow but real side channel for guessing attacks.
    // CryptographicOperations.FixedTimeEquals is the standard .NET primitive
    // for exactly this comparison.
    private static bool FixedTimeEquals(string expectedHex, string actualHex) =>
        expectedHex.Length == actualHex.Length &&
        CryptographicOperations.FixedTimeEquals(Convert.FromHexString(expectedHex), Convert.FromHexString(actualHex));
}
