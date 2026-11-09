namespace user_service.Services.Interfaces;

// Plain English first: think of a pizza shop that can notify you by phone call,
// text, or email. The kitchen (OtpService) only decides "the pizza is ready
// and here is the pickup code" - it does not care how you hear about it. Each
// notification method is a separate "channel" that knows how to reach you and
// whether it even can (no email address on file = the email channel can't).
// Turning a channel on or off is a shop setting, not a kitchen rewrite.
//
// Strategy (classic pattern) + Open/Closed (SOLID O): OtpService depends on
// this one interface and receives every ENABLED channel from DI. Adding a
// WhatsApp channel later = one new class + one line in Program.cs; OtpService
// is not touched. Without it, each new delivery method would add another
// if/else branch inside the OTP business rules.
public interface IOtpDeliveryChannel
{
    // Short identifier used in logs only ("email", "sms").
    string Name { get; }

    // Guard for "this channel is enabled but this particular user can't be
    // reached through it" (e.g. email channel, account with no email).
    bool CanDeliver(OtpRecipient recipient);

    Task SendAsync(OtpRecipient recipient, string code, int expiryMinutes, CancellationToken cancellationToken = default);
}

// Everything a channel might need to reach one person. A channel picks the
// field it cares about, so adding a channel never changes OtpService's
// signature.
public record OtpRecipient(string PhoneNumber, string? Email);
