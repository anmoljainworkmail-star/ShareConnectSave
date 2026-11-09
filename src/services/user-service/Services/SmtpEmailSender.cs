namespace user_service.Services;

using System.Net;
using System.Net.Mail;
using user_service.Services.Interfaces;

// Plain English first: SMTP is the decades-old "post office protocol" every
// email provider speaks. Your app is a customer walking up to the provider's
// counter (host:port), showing an ID (username + password), and handing over
// a letter (from, to, subject, body). Because EVERY provider - Brevo, Gmail,
// Mailtrap, SendGrid, Amazon SES - exposes this same counter, writing the
// sender against SMTP instead of one vendor's REST API means switching
// provider is five env vars, zero code. That is the Open/Closed and
// Dependency Inversion payoff here (SOLID O, D).
//
// Free options that work with these env vars as-is:
//   - Brevo   : smtp-relay.brevo.com:587, ~300 emails/day free, any recipient
//               once your sender address is verified (no domain needed).
//   - Gmail   : smtp.gmail.com:587 with a 16-char App Password (needs 2FA on).
//   - Mailtrap: sandbox.smtp.mailtrap.io:2525 - catches mail in a web inbox,
//               never delivers to real users; ideal for dev since a typo can
//               never email a stranger.
//
// No NuGet package: System.Net.Mail.SmtpClient ships with .NET, same "one
// fewer third-party dependency" reasoning as TwilioClient/GoogleTokenValidator.
// Trade-off worth knowing: Microsoft marks SmtpClient "not recommended for new
// development" in favour of MailKit (better TLS/async/edge-case handling).
// For one transactional email per OTP it is adequate; if volume or reliability
// needs grow, only this class changes - nobody else knows SmtpClient exists.
public class SmtpEmailSender : IEmailSender
{
    private readonly ILogger<SmtpEmailSender> _logger;
    private readonly string _host;
    private readonly int _port;
    private readonly string _username;
    private readonly string _password;
    private readonly string _fromAddress;

    public SmtpEmailSender(IConfiguration configuration, ILogger<SmtpEmailSender> logger)
    {
        _logger = logger;

        // Fail Fast + No Hardcoded Config (project rule): a missing credential
        // must blow up at startup/first resolve with a clear name, not surface
        // as a vague 500 on the first user's OTP request.
        _host = configuration["SMTP_HOST"]
            ?? throw new InvalidOperationException("SMTP_HOST is not set");
        _username = configuration["SMTP_USERNAME"]
            ?? throw new InvalidOperationException("SMTP_USERNAME is not set");
        _password = configuration["SMTP_PASSWORD"]
            ?? throw new InvalidOperationException("SMTP_PASSWORD is not set");
        _fromAddress = configuration["SMTP_FROM"]
            ?? throw new InvalidOperationException("SMTP_FROM is not set");

        var portText = configuration["SMTP_PORT"] ?? "587";
        if (!int.TryParse(portText, out _port))
        {
            throw new InvalidOperationException($"SMTP_PORT must be an integer, got '{portText}'");
        }
    }

    public async Task SendAsync(string toAddress, string subject, string body, CancellationToken cancellationToken = default)
    {
        // A new SmtpClient per send is intentional: the class is not
        // thread-safe and holds a live socket, so sharing one instance across
        // concurrent requests (a Singleton) would be the bug, not the saving.
        // EnableSsl on 587 means STARTTLS - the credentials above are never
        // sent in the clear.
        using var client = new SmtpClient(_host, _port)
        {
            EnableSsl = true,
            Credentials = new NetworkCredential(_username, _password),
        };

        using var message = new MailMessage(_fromAddress, toAddress, subject, body);

        try
        {
            await client.SendMailAsync(message, cancellationToken);
        }
        catch (SmtpException ex)
        {
            // Fail closed (same stance as TwilioClient): a provider outage is
            // OUR failure. Throwing lets the controller 500 instead of telling
            // the user "code sent" when nothing was. The destination address
            // is deliberately not logged - it is personal data and the
            // exception already names the provider-side status.
            _logger.LogError(ex, "SMTP send failed via {Host}:{Port} with status {StatusCode}", _host, _port, ex.StatusCode);
            throw;
        }
    }
}
