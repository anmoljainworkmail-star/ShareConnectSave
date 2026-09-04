namespace user_service.Services;

using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Services.Interfaces;

// Strategy (classic pattern) - the production implementation of
// IFaceMatchService, wired in by Program.cs whenever IDENTITY_VERIFY_STUB is
// not set to "true". Same "no SDK package" reasoning as TwilioClient: this
// calls Azure's Face REST API directly over HttpClient rather than adding
// the Azure.AI.Vision.Face NuGet package - one fewer third-party dependency
// to trust, and adding a package is itself a Tech-Lead-run codegen-adjacent
// step (changes the .csproj) per this project's CLI-command policy.
//
// Azure Face API's "Verify" operation needs a faceId for each image first
// (from the Detect operation), then compares the two faceIds and returns a
// confidence score - this class makes both calls per comparison.
public class AzureFaceMatchService : IFaceMatchService
{
    // Named Constant (ticket rule: never bury 0.8 as a literal inside
    // unrelated code) - this is the ONE place the match/mismatch line is
    // drawn. A different provider's implementation of IFaceMatchService is
    // free to use an entirely different scoring scheme (Open/Closed, SOLID
    // O) - the threshold is a detail of THIS provider's confidence scale,
    // not a cross-cutting business rule shared by every implementation.
    internal const double MatchConfidenceThreshold = 0.8;

    private readonly HttpClient _httpClient;
    private readonly ILogger<AzureFaceMatchService> _logger;
    private readonly string _subscriptionKey;

    public AzureFaceMatchService(HttpClient httpClient, IOptions<AzureFaceOptions> options, ILogger<AzureFaceMatchService> logger)
    {
        var azureFaceOptions = options.Value;

        // No Hardcoded Config (project rule): fail fast at construction, not
        // on the first request, if a required credential/endpoint is
        // missing - identical discipline to GoogleTokenValidator/TwilioClient.
        // This constructor only ever runs when Program.cs picked THIS
        // implementation (IDENTITY_VERIFY_STUB is not "true"), so requiring
        // real Azure config here never blocks stub-mode local dev.
        if (string.IsNullOrWhiteSpace(azureFaceOptions.Endpoint))
        {
            throw new InvalidOperationException("AzureFace__Endpoint is not set");
        }

        _subscriptionKey = azureFaceOptions.Key
            ?? throw new InvalidOperationException("AzureFace__Key is not set");

        _httpClient = httpClient;
        _httpClient.BaseAddress = new Uri(azureFaceOptions.Endpoint.TrimEnd('/') + "/");
        _logger = logger;
    }

    public async Task<FaceMatchOutcome> CompareAsync(byte[] basePhotoBytes, byte[] selfieBytes, CancellationToken cancellationToken)
    {
        var baseFaceId = await DetectSingleFaceIdAsync(basePhotoBytes, cancellationToken);
        var selfieFaceId = await DetectSingleFaceIdAsync(selfieBytes, cancellationToken);

        // Guard clause: no face detected in either image is a mismatch, not
        // an error - there is nothing to compare, so this can never be a
        // match, and no Verify call is worth making.
        if (baseFaceId is null || selfieFaceId is null)
        {
            _logger.LogInformation("Azure Face detect found no usable face in {Image}", baseFaceId is null ? "base photo" : "selfie");
            return new FaceMatchOutcome(IsMatch: false);
        }

        var confidence = await VerifyAsync(baseFaceId, selfieFaceId, cancellationToken);

        // The 0.8 threshold (see MatchConfidenceThreshold above) is applied
        // here, not left to Azure's own "isIdentical" flag - our own named
        // constant is the single source of truth for what counts as a match
        // in this platform, independent of whatever default threshold Azure
        // itself uses internally.
        return new FaceMatchOutcome(IsMatch: confidence > MatchConfidenceThreshold);
    }

    private async Task<string?> DetectSingleFaceIdAsync(byte[] imageBytes, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "face/v1.0/detect?returnFaceId=true");
        request.Headers.Add("Ocp-Apim-Subscription-Key", _subscriptionKey);
        request.Content = new ByteArrayContent(imageBytes);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");

        using var response = await SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);

        using var document = JsonDocument.Parse(body);
        var faces = document.RootElement;

        // Zero faces detected (an empty selfie, a photo of a landscape) is a
        // legitimate response shape from Azure, not a failure - the caller
        // treats a null faceId as "no match possible" rather than throwing.
        return faces.GetArrayLength() == 0
            ? null
            : faces[0].GetProperty("faceId").GetString();
    }

    private async Task<double> VerifyAsync(string faceId1, string faceId2, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "face/v1.0/verify");
        request.Headers.Add("Ocp-Apim-Subscription-Key", _subscriptionKey);
        var payload = JsonSerializer.Serialize(new { faceId1, faceId2 });
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");

        using var response = await SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);

        using var document = JsonDocument.Parse(body);
        return document.RootElement.GetProperty("confidence").GetDouble();
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        HttpResponseMessage response;
        try
        {
            response = await _httpClient.SendAsync(request, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            // Fail closed, same discipline as TwilioClient.SendSmsAsync: an
            // Azure outage is OUR failure, not the caller's "your face
            // didn't match" - surfaced as an exception so
            // GlobalExceptionHandler produces a 500, never a false
            // IDENTITY_MISMATCH.
            _logger.LogError(ex, "Azure Face API request failed");
            throw;
        }

        if (!response.IsSuccessStatusCode)
        {
            var errorBody = await response.Content.ReadAsStringAsync(cancellationToken);
            _logger.LogError("Azure Face API returned {StatusCode}: {Body}", (int)response.StatusCode, errorBody);
            throw new InvalidOperationException($"Azure Face API call failed with status {(int)response.StatusCode}");
        }

        return response;
    }
}
