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
// exceptional condition, so it is returned, not thrown. LockedUntil/OnboardingComplete
// are only populated for the outcome that needs them (Locked / Verified
// respectively); null in the other cases.
//
// Post-T018 fix: NewAccessToken closes the same stale-claim gap
// UserProfileController.UpdateMyProfile already handles for PATCH /users/me
// (see that method's comment) — it was missed here because T017 shipped
// before "onboarding_complete" existed as a JWT claim at all.
// IsProfileComplete() flipping IsOnboardingComplete to true mid-verification
// changes a claim baked into the caller's token exactly the same way a PATCH
// does, so this needed the identical reissue treatment. Populated only on
// Verified AND only when the value actually changed — see
// OtpService.VerifyOtpAsync. (OnboardingComplete itself, separately, used to
// be surfaced by overloading Status with "incomplete"/"active" — see
// User.IsOnboardingComplete's comment for why that was split out.)
public record OtpVerificationOutcome(OtpVerificationResult Result, DateTime? LockedUntil, bool? OnboardingComplete, string? NewAccessToken = null);
