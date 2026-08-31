namespace user_service.Services.Interfaces;

// Interface Segregation (SOLID I): OtpController depends on this narrow
// contract, not on OtpAttempt/User repositories or ITwilioClient directly —
// swapping the whole OTP implementation (a different SMS provider, an
// in-memory fake for tests) never touches the controller.
public interface IOtpService
{
    Task SendOtpAsync(string phoneNumber, CancellationToken cancellationToken = default);

    Task<OtpVerificationOutcome> VerifyOtpAsync(
        long userId,
        string phoneNumber,
        string code,
        CancellationToken cancellationToken = default);
}

// The outcomes VerifyOtp can produce, each mapping to a different HTTP
// status in the controller.
public enum OtpVerificationResult
{
    Verified,
    InvalidCode,
    Locked,

    // T017 fix: the code was correct, but the phone is already verified on a
    // DIFFERENT account — a conflict, not an invalid-code or lockout case.
    PhoneAlreadyInUse,
}

// Result Object: an expected "wrong code" or "locked" outcome is routine
// control flow the controller must branch on to pick a status code — not an
// exceptional condition, so it is returned, not thrown. LockedUntil/UserStatus
// are only populated for the outcome that needs them (Locked / Verified
// respectively); null in the other cases.
public record OtpVerificationOutcome(OtpVerificationResult Result, DateTime? LockedUntil, string? UserStatus);
