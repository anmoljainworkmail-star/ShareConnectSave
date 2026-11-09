namespace user_service.Services;

using user_service.Services.Interfaces;

// Adapter (classic pattern): wraps the existing ITwilioClient (real
// TwilioClient or StubTwilioClient, chosen by TWILIO_STUB) as an OTP delivery
// channel. The Twilio code itself is untouched - this is the whole point of
// keeping phone OTP: re-enabling it is a config change (OTP_CHANNELS), not a
// rebuild.
public class SmsOtpChannel : IOtpDeliveryChannel
{
    private readonly ITwilioClient _twilioClient;

    public SmsOtpChannel(ITwilioClient twilioClient)
    {
        _twilioClient = twilioClient;
    }

    public string Name => "sms";

    // The phone number is validated at the controller boundary, so every
    // request that reaches here has one.
    public bool CanDeliver(OtpRecipient recipient) => !string.IsNullOrWhiteSpace(recipient.PhoneNumber);

    public Task SendAsync(OtpRecipient recipient, string code, int expiryMinutes, CancellationToken cancellationToken = default) =>
        _twilioClient.SendSmsAsync(
            recipient.PhoneNumber,
            $"Your ShareConnectSave verification code is {code}. It expires in {expiryMinutes} minutes.",
            cancellationToken);
}
