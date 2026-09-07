namespace user_service.Services;

using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Services.Interfaces;

// Typed HttpClient (same pooling/DNS-refresh reasoning as
// IGoogleTokenValidator/ITwilioClient's AddHttpClient<TInterface,
// TImplementation>() registrations in Program.cs): still needed for the
// genuinely external case (see below) — a plain GET is all that case ever
// needs.
//
// Fix (found live during integration testing): photo_url is NOT always a URL
// this service can actually reach over HTTP. IProfilePhotoStorageService.
// SavePhotoAsync's comment already explains photo_url can be either a
// genuinely external, Google-hosted URL (payload.Picture at sign-in) OR our
// own locally-stored file, returned with a PUBLIC-facing address built from
// ProfilePhotoOptions.BaseUrl (see that class's comment: the "/user" prefix
// only exists at the gateway boundary, stripped before this service ever
// sees a request). For the local case, this class was making an HTTP call
// to ITSELF, through whatever public host happened to be baked into the URL
// at upload time (api-gateway's address, before or after that fix) - which
// 404s regardless, because this service's own app.UseStaticFiles() serves
// the same file at the un-prefixed path, never "/user/...". Reading the file
// straight off local disk when the URL matches our own storage convention
// sidesteps host/port confusion entirely (the check below never even looks
// at scheme/host, only the path's filename) and is strictly better than any
// URL-rewriting fix would have been - one less unnecessary network
// round-trip through the very same process for a file already sitting on
// its own disk. A genuinely external URL (Google's) never matches
// ProfilePhotoOptions.BaseUrl and falls through to the HTTP path unchanged.
public class BasePhotoDownloadService : IBasePhotoDownloadService
{
    private readonly HttpClient _httpClient;
    private readonly ProfilePhotoOptions _photoOptions;
    private readonly IWebHostEnvironment _env;
    private readonly ILogger<BasePhotoDownloadService> _logger;

    public BasePhotoDownloadService(
        HttpClient httpClient,
        IOptions<ProfilePhotoOptions> photoOptions,
        IWebHostEnvironment env,
        ILogger<BasePhotoDownloadService> logger)
    {
        _httpClient = httpClient;
        _photoOptions = photoOptions.Value;
        _env = env;
        _logger = logger;
    }

    public async Task<byte[]> DownloadAsync(string photoUrl, CancellationToken cancellationToken)
    {
        var localPath = TryResolveLocalPath(photoUrl);
        if (localPath is not null)
        {
            try
            {
                return await File.ReadAllBytesAsync(localPath, cancellationToken);
            }
            catch (IOException ex)
            {
                // Same fail-closed discipline as the HTTP catch below: a file
                // this service's own upload flow should have created, but
                // can't now read, is OUR failure, not evidence of a mismatch.
                _logger.LogError(ex, "Failed to read local base photo for identity verification: {Path}", localPath);
                throw;
            }
        }

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

    // Matches on the URL's PATH only (never scheme/host) — deliberately
    // robust to whatever public host/port got baked into photo_url at upload
    // time, since none of that is relevant once we already know it's one of
    // our own locally-stored files. Path traversal guarded explicitly since
    // this still derives a filesystem path from external-ish input, even
    // though photoUrl is never directly attacker-supplied (it only ever
    // comes from our own SavePhotoAsync or Google's sign-in payload).
    private string? TryResolveLocalPath(string photoUrl)
    {
        var path = Uri.TryCreate(photoUrl, UriKind.Absolute, out var uri) ? uri.AbsolutePath : photoUrl;

        var marker = _photoOptions.BaseUrl.TrimEnd('/') + "/";
        var markerIndex = path.IndexOf(marker, StringComparison.Ordinal);
        if (markerIndex < 0)
        {
            return null;
        }

        // Fix (caught in review): a bare `..`-substring check doesn't stop a
        // ROOTED path from reaching Path.Combine below — Path.Combine's
        // documented behavior is to discard its first argument ENTIRELY and
        // return the second one verbatim when that second argument is
        // rooted (e.g. an extracted "fileName" of "/etc/passwd" or
        // "C:\secrets\file"), which would read a file completely outside
        // storageDir. Path.GetFileName strips every directory component —
        // both ".." segments AND a leading root — down to just the final
        // path segment, closing both escape routes in the one call rather
        // than needing a second, separate rooted-path check.
        var fileName = Path.GetFileName(path[(markerIndex + marker.Length)..]);
        if (string.IsNullOrWhiteSpace(fileName))
        {
            return null;
        }

        var storageDir = Path.IsPathRooted(_photoOptions.StoragePath)
            ? _photoOptions.StoragePath
            : Path.Combine(_env.ContentRootPath, _photoOptions.StoragePath);

        var absolutePath = Path.Combine(storageDir, fileName);
        return File.Exists(absolutePath) ? absolutePath : null;
    }
}
