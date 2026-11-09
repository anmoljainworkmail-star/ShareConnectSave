namespace user_service.Services.Interfaces;

// Dependency Inversion (SOLID D) + Interface Segregation (SOLID I): OtpService
// only needs "deliver this text to this address" - it must not know whether
// that happens over SMTP, a vendor HTTP API, or a log line. Deliberately
// generic (to/subject/body, nothing OTP-specific) so the same abstraction
// later serves any other email the User Service might send, and so swapping
// providers (Brevo -> Gmail -> Resend) is a configuration change, not a code
// change - the same reason ITwilioClient existed for SMS.
public interface IEmailSender
{
    Task SendAsync(string toAddress, string subject, string body, CancellationToken cancellationToken = default);
}
