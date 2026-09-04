namespace user_service.Services;

using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using user_service.Dtos;
using user_service.Models;
using user_service.Repositories.Interfaces;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S), same shape as UserProfileService: this
// class owns the entire "prove the selfie matches the base photo and flip
// the badge" business rule. It does not implement photo upload (PATCH
// /users/me, a separate ticket) and does not touch trust scoring or
// discovery eligibility - it only orchestrates its four collaborators
// (Dependency Inversion, SOLID D - all four are injected interfaces, never
// concrete classes) and returns a Result Object for
// IdentityVerificationController to translate into an HTTP response.
public class IdentityVerificationService : IIdentityVerificationService
{
    private readonly IUserRepository _userRepository;
    private readonly IIdentityVerificationRepository _identityVerificationRepository;
    private readonly IFaceMatchService _faceMatchService;
    private readonly IBasePhotoDownloadService _basePhotoDownloadService;

    public IdentityVerificationService(
        IUserRepository userRepository,
        IIdentityVerificationRepository identityVerificationRepository,
        IFaceMatchService faceMatchService,
        IBasePhotoDownloadService basePhotoDownloadService)
    {
        _userRepository = userRepository;
        _identityVerificationRepository = identityVerificationRepository;
        _faceMatchService = faceMatchService;
        _basePhotoDownloadService = basePhotoDownloadService;
    }

    public async Task<VerifyIdentityOutcome> VerifyIdentityAsync(long userId, IFormFile? selfie, CancellationToken cancellationToken)
    {
        // Guard clause (project convention): reject an obviously-unusable
        // upload before ever touching the database or any external service -
        // no wasted I/O on a request that can't possibly succeed. Same
        // discipline as UserProfileService.UploadPhotoAsync's identical check.
        if (selfie is null || selfie.Length == 0)
        {
            return Invalid("A selfie image file (multipart field \"selfie\") is required.");
        }

        var user = await _userRepository.GetByIdAsync(userId);
        if (user is null)
        {
            return new VerifyIdentityOutcome(VerifyIdentityResult.NotFound, null, null);
        }

        // Pre-condition Guard Clause / fail fast (this ticket's headline
        // concept): read photo_url off the profile and stop HERE, before any
        // face-matching code runs (no download, no IFaceMatchService call,
        // process.env.AZURE_FACE_* config never touched on this path), if
        // there is nothing to compare against. This is a guard clause both
        // for correctness (you cannot compare against nothing) and for
        // cost/latency - Azure Face API is a paid, network-bound external
        // call, and every request that reaches it for a user with no base
        // photo is money and time spent on a call that was already known to
        // fail before it started.
        if (string.IsNullOrWhiteSpace(user.PhotoUrl))
        {
            return new VerifyIdentityOutcome(
                VerifyIdentityResult.NoBasePhoto,
                "No base profile photo on file. Upload one via PATCH /users/me before verifying your identity.",
                null);
        }

        var basePhotoBytes = await _basePhotoDownloadService.DownloadAsync(user.PhotoUrl, cancellationToken);
        var selfieBytes = await ReadAllBytesAsync(selfie, cancellationToken);

        // Strategy (classic pattern): CompareAsync's actual implementation -
        // Azure-backed or stub-backed - was chosen once, at startup, by
        // Program.cs based on IDENTITY_VERIFY_STUB. This class has no idea
        // which one it is talking to.
        var matchOutcome = await _faceMatchService.CompareAsync(basePhotoBytes, selfieBytes, cancellationToken);

        // Audit trail (ticket rule: do not skip writing a row on the failed/
        // mismatch path) - every attempt, successful or not, gets its own
        // identity_verifications row. This table is intentionally
        // insert-only per attempt (see IdentityVerification's class comment
        // on why verification is its own table) rather than one row per user
        // that gets overwritten - a history of failed attempts matters for
        // future abuse/review tooling (Admin Service) as much as the
        // successful one does.
        var verification = new IdentityVerification
        {
            UserId = userId,
            Status = matchOutcome.IsMatch ? "verified" : "failed",
            VerifiedAt = matchOutcome.IsMatch ? DateTime.UtcNow : null,
        };
        await _identityVerificationRepository.AddAsync(verification);

        if (!matchOutcome.IsMatch)
        {
            // Note: no raw confidence score or Azure error detail is
            // threaded through here - FaceMatchOutcome never carried one
            // past the face-match boundary in the first place (see that
            // record's comment). The client only ever learns "mismatch",
            // never why.
            return new VerifyIdentityOutcome(VerifyIdentityResult.Mismatch, "Selfie does not match the profile photo on file.", null);
        }

        user.IdentityBadge = true;

        try
        {
            await _userRepository.UpdateAsync(user);
        }
        catch (DbUpdateConcurrencyException)
        {
            // Optimistic Concurrency (see User.RowVersion's class comment):
            // same reasoning as UserProfileService's identical catch block -
            // a concurrent write to this user row (e.g. PATCH /users/me
            // racing this request) means the RowVersion this UPDATE's WHERE
            // clause expected no longer matches. The identity_verifications
            // row above already recorded a "verified" attempt regardless -
            // only the badge flip on the user row needs a retry.
            return new VerifyIdentityOutcome(
                VerifyIdentityResult.Conflict,
                "User profile was modified concurrently. Please retry.",
                null);
        }

        return new VerifyIdentityOutcome(VerifyIdentityResult.Success, null, UserProfileDto.FromUser(user));
    }

    private static async Task<byte[]> ReadAllBytesAsync(IFormFile file, CancellationToken cancellationToken)
    {
        using var memoryStream = new MemoryStream();
        await file.CopyToAsync(memoryStream, cancellationToken);
        return memoryStream.ToArray();
    }

    private static VerifyIdentityOutcome Invalid(string message) =>
        new(VerifyIdentityResult.InvalidRequest, message, null);
}
