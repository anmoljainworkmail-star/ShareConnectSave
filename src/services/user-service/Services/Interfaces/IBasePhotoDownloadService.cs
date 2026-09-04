namespace user_service.Services.Interfaces;

// Interface Segregation (SOLID I) + Single Responsibility (SOLID S):
// ProfilePhotoStorageService (T018) only knows how to SAVE an uploaded file
// and hand back a URL - it has no download capability, and adding one to it
// would mix "how do we store a photo" with "how do we fetch someone else's
// already-stored photo" into one class for no shared reason. This is a
// separate, narrow contract for the one thing IdentityVerificationService
// actually needs: turn photo_url into bytes, so they can be compared against
// a freshly-uploaded selfie.
public interface IBasePhotoDownloadService
{
    Task<byte[]> DownloadAsync(string photoUrl, CancellationToken cancellationToken);
}
