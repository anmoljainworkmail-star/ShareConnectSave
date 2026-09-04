namespace user_service.Services;

using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Services.Interfaces;

// Local-disk implementation (Strategy — see IProfilePhotoStorageService's
// comment): this is a deliberately simple dev-stage strategy. No cloud
// storage account exists anywhere in this project's infra yet (.env.example
// and docker-compose.yml provision SQL Server/MongoDB/Kafka/Redis, no
// S3/Azure Blob), so writing to a directory served by ASP.NET Core's own
// static file middleware (Program.cs's app.UseStaticFiles()) is the
// smallest thing that actually works end-to-end today. The interface above
// is what makes this swappable for a real object-store implementation later
// without any controller change.
public class ProfilePhotoStorageService : IProfilePhotoStorageService
{
    private readonly ProfilePhotoOptions _options;
    private readonly IWebHostEnvironment _env;
    private readonly ILogger<ProfilePhotoStorageService> _logger;

    public ProfilePhotoStorageService(
        IOptions<ProfilePhotoOptions> options,
        IWebHostEnvironment env,
        ILogger<ProfilePhotoStorageService> logger)
    {
        _options = options.Value;
        _env = env;
        _logger = logger;
    }

    public async Task<string> SavePhotoAsync(long userId, IFormFile photo, string requestBaseUrl, CancellationToken cancellationToken)
    {
        var extension = Path.GetExtension(photo.FileName);
        if (string.IsNullOrWhiteSpace(extension))
        {
            extension = ".jpg";
        }

        // One physical file per upload, never overwritten in place: a
        // GUID-suffixed filename means a second upload can't collide with
        // (or race) a still-in-flight read of the previous one. The user
        // row's photo_url column is what actually "replaces" the old
        // photo — the old file on disk is just an orphan, not a correctness
        // problem, and cleanup of orphans is a separate concern from this
        // ticket.
        var fileName = $"{userId}-{Guid.NewGuid():N}{extension}";

        var storageDir = Path.IsPathRooted(_options.StoragePath)
            ? _options.StoragePath
            : Path.Combine(_env.ContentRootPath, _options.StoragePath);

        Directory.CreateDirectory(storageDir);

        var absolutePath = Path.Combine(storageDir, fileName);
        await using (var stream = new FileStream(absolutePath, FileMode.Create, FileAccess.Write))
        {
            await photo.CopyToAsync(stream, cancellationToken);
        }

        _logger.LogInformation("Saved profile photo for user {UserId} to {Path}", userId, absolutePath);

        var relativeUrl = $"{_options.BaseUrl.TrimEnd('/')}/{fileName}";
        return $"{requestBaseUrl.TrimEnd('/')}{relativeUrl}";
    }
}
