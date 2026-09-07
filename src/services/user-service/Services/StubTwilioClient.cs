namespace user_service.Services;

using user_service.Services.Interfaces;

// Strategy (classic pattern) — dev-only implementation of ITwilioClient, same
// shape as StubFaceMatchService/IDENTITY_VERIFY_STUB (T019). Wired in by
// Program.cs ONLY when TWILIO_STUB=true, so local development and CI can
// exercise the entire OTP flow (send, verify, lockout) without a real Twilio
// account.
//
// Found necessary during integration testing, not a hypothetical: Twilio
// trial accounts reject free-form SMS bodies sent to +91 numbers outright
// (error 572006, "Invalid template name — trial accounts can only use
// predefined SMS templates") — an account/carrier-compliance restriction
// (India's TRAI/DLT rules), not something fixable from this codebase. This
// class is what T017's own QA checklist already anticipated but never
// actually shipped ("Twilio client should be the stub/sandbox implementation
// ... check console/log output for the code instead of a real phone" —
// .claude/qa/T017.qa.md) — every prior manual QA pass ran with real Twilio
// credentials that happened to work for whatever number was tested, so the
// gap was never hit until testing against a real trial account + a real
// Indian number together.
//
// No network call is made, which is the entire point — logs the message at
// Information level so a developer can read the OTP code straight out of
// `docker compose logs user-service`, the same place StubFaceMatchService's
// silence would otherwise leave nothing to check.
public class StubTwilioClient : ITwilioClient
{
    private readonly ILogger<StubTwilioClient> _logger;

    public StubTwilioClient(ILogger<StubTwilioClient> logger)
    {
        _logger = logger;
    }

    public Task SendSmsAsync(string toPhoneNumber, string message, CancellationToken cancellationToken = default)
    {
        _logger.LogInformation(
            "TWILIO_STUB active — no real SMS sent. Would have sent to {ToPhoneNumber}: {Message}",
            toPhoneNumber,
            message);

        return Task.CompletedTask;
    }
}
