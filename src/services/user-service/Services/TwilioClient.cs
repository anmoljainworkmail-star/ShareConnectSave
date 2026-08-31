namespace user_service.Services;

using System.Net.Http.Headers;
using System.Text;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S): this class does exactly one thing — hand
// a phone number and a message to Twilio's REST API — the same shape as
// GoogleTokenValidator's relationship to Google's tokeninfo endpoint (T016).
// OtpService never touches HttpClient, Twilio's URL shape, or Basic Auth
// directly; it only knows the ITwilioClient abstraction.
//
// No Twilio NuGet package: like GoogleTokenValidator's raw call to Google's
// tokeninfo REST endpoint, this calls Twilio's Messages REST API directly
// over HttpClient instead of adding the official Twilio SDK package. Two
// reasons: one fewer third-party dependency to trust for a single REST call,
// and — project-specific — adding a NuGet package changes user-service.csproj
// the same way `dotnet ef migrations add` changes the Migrations folder: a
// generated/fetched artifact the Tech Lead runs themselves (`dotnet add
// package`), not something to route around by hand-editing the .csproj to
// match what that command would have produced.
public class TwilioClient : ITwilioClient
{
    private const string MessagesEndpointTemplate = "https://api.twilio.com/2010-04-01/Accounts/{0}/Messages.json";

    private readonly HttpClient _httpClient;
    private readonly ILogger<TwilioClient> _logger;
    private readonly string _accountSid;
    private readonly string _authToken;
    private readonly string _fromPhoneNumber;

    public TwilioClient(HttpClient httpClient, IConfiguration configuration, ILogger<TwilioClient> logger)
    {
        _httpClient = httpClient;
        _logger = logger;

        // No Hardcoded Config (project rule): identical pattern to
        // GoogleTokenValidator's GOOGLE_CLIENT_ID read — fail fast at
        // construction, not on the first request, if a required credential
        // is missing. Flat env var names (TWILIO_ACCOUNT_SID, not a nested
        // "Twilio:AccountSid" section) per the ticket's explicit spec.
        _accountSid = configuration["TWILIO_ACCOUNT_SID"]
            ?? throw new InvalidOperationException("TWILIO_ACCOUNT_SID is not set");
        _authToken = configuration["TWILIO_AUTH_TOKEN"]
            ?? throw new InvalidOperationException("TWILIO_AUTH_TOKEN is not set");
        _fromPhoneNumber = configuration["TWILIO_PHONE_NUMBER"]
            ?? throw new InvalidOperationException("TWILIO_PHONE_NUMBER is not set");
    }

    public async Task SendSmsAsync(string toPhoneNumber, string message, CancellationToken cancellationToken = default)
    {
        var requestUrl = string.Format(MessagesEndpointTemplate, _accountSid);

        using var request = new HttpRequestMessage(HttpMethod.Post, requestUrl);

        // Twilio's own auth scheme: HTTP Basic with {AccountSid}:{AuthToken}.
        // Never logged, never echoed back in any response — same "fail
        // closed on a boundary we don't control" discipline as
        // GoogleTokenValidator's audience check.
        var basicAuthValue = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{_accountSid}:{_authToken}"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic", basicAuthValue);

        request.Content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["To"] = toPhoneNumber,
            ["From"] = _fromPhoneNumber,
            ["Body"] = message,
        });

        HttpResponseMessage response;
        try
        {
            response = await _httpClient.SendAsync(request, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            // Fail closed, but distinguish from "invalid OTP": an SMS
            // provider outage is OUR failure, not the caller's — surfaced as
            // an exception so the controller 500s rather than silently
            // pretending a code was texted when it wasn't.
            _logger.LogError(ex, "Twilio SMS request failed for a masked destination number");
            throw;
        }

        if (!response.IsSuccessStatusCode)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            _logger.LogError("Twilio returned {StatusCode}: {Body}", (int)response.StatusCode, body);
            throw new InvalidOperationException($"Twilio SMS send failed with status {(int)response.StatusCode}");
        }
    }
}
