namespace user_service.Services;

using user_service.Services.Interfaces;

// Typed HttpClient (same pooling/DNS-refresh reasoning as
// IGoogleTokenValidator/ITwilioClient's AddHttpClient<TInterface,
// TImplementation>() registrations in Program.cs): a base photo's photo_url
// is always a fully-qualified, independently fetchable URL (see
// IProfilePhotoStorageService.SavePhotoAsync's comment - true whether the
// photo came from Google at sign-in or from POST /users/me/photo), so a
// plain GET is all this class ever needs to do.
public class BasePhotoDownloadService : IBasePhotoDownloadService
{
    private readonly HttpClient _httpClient;
    private readonly ILogger<BasePhotoDownloadService> _logger;

    public BasePhotoDownloadService(HttpClient httpClient, ILogger<BasePhotoDownloadService> logger)
    {
        _httpClient = httpClient;
        _logger = logger;
    }

    public async Task<byte[]> DownloadAsync(string photoUrl, CancellationToken cancellationToken)
    {
        try
        {
            return await _httpClient.GetByteArrayAsync(photoUrl, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            // Fail closed, same discipline as TwilioClient/AzureFaceMatchService:
            // a base photo that fails to download (dead link, storage outage)
            // is OUR failure, not evidence the selfie doesn't match - surfaced
            // as an exception so GlobalExceptionHandler produces a 500, never
            // a false IDENTITY_MISMATCH.
            _logger.LogError(ex, "Failed to download base photo for identity verification");
            throw;
        }
    }
}
