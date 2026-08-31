namespace user_service.Services.Interfaces;

// Dependency Inversion (SOLID D): OtpService depends on this abstraction,
// never on a concrete Twilio HTTP client — a unit test substitutes a fake
// that just records "would have sent X to Y" instead of making a real
// network call to a paid SMS provider on every test run. Same reasoning as
// IGoogleTokenValidator's relationship to GoogleTokenValidator (T016).
public interface ITwilioClient
{
    Task SendSmsAsync(string toPhoneNumber, string message, CancellationToken cancellationToken = default);
}
