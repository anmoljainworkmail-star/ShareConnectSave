namespace user_service.Services;

using user_service.Services.Interfaces;

// Strategy (classic pattern): the no-network sibling of SmtpEmailSender, picked
// at startup by Program.cs when EMAIL_STUB=true. Same job StubTwilioClient
// does for SMS - lets CI and offline dev run the whole OTP flow
// (send, verify, lockout) with nothing external. The body (which contains the
// code) lands in `docker compose logs user-service`.
//
// Safe only because it is never the default in a deployed environment: logging
// a live OTP is exactly the leak a real sender exists to avoid.
public class LoggingEmailSender : IEmailSender
{
    private readonly ILogger<LoggingEmailSender> _logger;

    public LoggingEmailSender(ILogger<LoggingEmailSender> logger)
    {
        _logger = logger;
    }

    public Task SendAsync(string toAddress, string subject, string body, CancellationToken cancellationToken = default)
    {
        _logger.LogInformation(
            "EMAIL_STUB active - no real email sent. Would have sent to {ToAddress} | {Subject} | {Body}",
            toAddress,
            subject,
            body);

        return Task.CompletedTask;
    }
}
