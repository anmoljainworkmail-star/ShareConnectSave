namespace user_service.Services.Interfaces;

using Microsoft.AspNetCore.Http;

// Dependency Inversion (SOLID D) + Single Responsibility (SOLID S):
// UserProfileController only knows "hand this file to something that saves
// photos and gives back a URL" — it has no idea whether that's local disk
// (today's only implementation) or a future Azure Blob/S3 client. Swapping
// the implementation later means changing one Program.cs DI registration
// line, not touching the controller. Same Strategy-shaped reasoning as
// CLAUDE.md's Resilience4j/Decorator example: the interface is the stable
// seam, the implementation behind it is free to change.
public interface IProfilePhotoStorageService
{
    // requestBaseUrl (e.g. "https://api.shareconnectsave.example") is passed
    // in per call, not cached on the service, because it depends on the
    // INCOMING request's scheme/host — not something a service instance can
    // know about itself. The returned photo_url must be a fully-qualified,
    // independently fetchable URL: T019's face-match step downloads whatever
    // is in photo_url the same way whether it came from Google or from here,
    // so this can never return a bare relative path.
    Task<string> SavePhotoAsync(long userId, IFormFile photo, string requestBaseUrl, CancellationToken cancellationToken);
}
