namespace user_service.Services;

using user_service.Services.Interfaces;

// Adapter (classic pattern): translates the OTP-shaped request ("deliver this
// code to this person") into the generic email shape IEmailSender understands
// (to/subject/body). The wording lives here, not in OtpService, so each
// channel can phrase the message for its medium - an email can be a sentence,
// an SMS must stay short.
public class EmailOtpChannel : IOtpDeliveryChannel
{
    private readonly IEmailSender _emailSender;

    public EmailOtpChannel(IEmailSender emailSender)
    {
        _emailSender = emailSender;
    }

    public string Name => "email";

    public bool CanDeliver(OtpRecipient recipient) => !string.IsNullOrWhiteSpace(recipient.Email);

    public Task SendAsync(OtpRecipient recipient, string code, int expiryMinutes, CancellationToken cancellationToken = default) =>
        _emailSender.SendAsync(
            recipient.Email!,
            "Your Karwan verification code",
            $"Your Karwan verification code is {code}. It expires in {expiryMinutes} minutes. If you did not request this, ignore this email.",
            cancellationToken);
}
