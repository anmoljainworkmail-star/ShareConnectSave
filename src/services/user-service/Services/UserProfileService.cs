namespace user_service.Services;

using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Dtos;
using user_service.Events;
using user_service.Repositories.Interfaces;
using user_service.Services.Interfaces;

// Single Responsibility (SOLID S), same shape as OtpService: this class owns
// every profile business rule — field validation, the stale-JWT-claim
// reissue decision, and photo upload constraints. It talks to
// IUserRepository/IProfilePhotoStorageService (Dependency Inversion, SOLID D)
// and returns Result Objects (UpdateProfileOutcome/PhotoUploadOutcome) for
// UserProfileController to translate into HTTP responses — the controller
// itself no longer has any business rule to get wrong.
public class UserProfileService : IUserProfileService
{
    // Open/Closed (SOLID O): adding a new allowed gender/status value later
    // means adding an entry to one of these two lookups, not touching the
    // validation branch in UpdateProfileAsync below.
    //
    // Values map lower-case wire input to the exact casing already stored on
    // User.Gender elsewhere in this codebase (User.cs's default
    // "Unspecified", User.IsProfileComplete()'s Ordinal comparison against
    // "Unspecified") — storing anything other than this exact casing would
    // silently break that check.
    private static readonly Dictionary<string, string> AllowedGenders = new(StringComparer.OrdinalIgnoreCase)
    {
        ["female"] = "Female",
        ["male"] = "Male",
        ["unspecified"] = "Unspecified",
    };

    // Post-T018 fix: Status used to also carry OtpService/AuthController's
    // onboarding-lifecycle values ("incomplete" -> "active") until a PATCH
    // here writing "looking"/"unavailable" into that same column was found
    // to silently overwrite that signal — see User.IsOnboardingComplete's
    // class comment for the full story. This column is now exclusively the
    // user-owned availability toggle validated below.
    private static readonly HashSet<string> AllowedStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        "looking",
        "unavailable",
    };

    private readonly IUserRepository _userRepository;
    private readonly IJwtIssuer _jwtIssuer;
    private readonly IProfilePhotoStorageService _photoStorageService;
    private readonly IUserVerifiedEventPublisher _userVerifiedEventPublisher;
    private readonly ProfilePhotoOptions _photoOptions;

    public UserProfileService(
        IUserRepository userRepository,
        IJwtIssuer jwtIssuer,
        IProfilePhotoStorageService photoStorageService,
        IUserVerifiedEventPublisher userVerifiedEventPublisher,
        IOptions<ProfilePhotoOptions> photoOptions)
    {
        _userRepository = userRepository;
        _jwtIssuer = jwtIssuer;
        _photoStorageService = photoStorageService;
        _userVerifiedEventPublisher = userVerifiedEventPublisher;
        _photoOptions = photoOptions.Value;
    }

    public async Task<UserProfileDto?> GetProfileAsync(long userId)
    {
        var user = await _userRepository.GetByIdAsync(userId);
        return user is null ? null : UserProfileDto.FromUser(user);
    }

    public async Task<UpdateProfileOutcome> UpdateProfileAsync(long userId, UpdateProfileRequest request)
    {
        // Validate everything before mutating anything (Guard Clause
        // convention applied at the request-shape level, not just per
        // field): a request with one bad field must not leave the row
        // partially updated with the other, valid fields.
        string? normalizedGender = null;
        if (request.Gender is not null)
        {
            if (!AllowedGenders.TryGetValue(request.Gender, out normalizedGender))
            {
                return Invalid($"gender must be one of: {string.Join(", ", AllowedGenders.Keys)}.");
            }
        }

        if (request.Status is not null && !AllowedStatuses.Contains(request.Status))
        {
            return Invalid($"status must be one of: {string.Join(", ", AllowedStatuses)}.");
        }

        if (request.Name is not null && string.IsNullOrWhiteSpace(request.Name))
        {
            return Invalid("name must not be blank.");
        }

        if (request.Name is not null && request.Name.Length > 200)
        {
            return Invalid("name must not exceed 200 characters.");
        }

        if (request.PreferredLanguage is not null && string.IsNullOrWhiteSpace(request.PreferredLanguage))
        {
            return Invalid("preferred_language must not be blank.");
        }

        if (request.PreferredLanguage is not null && request.PreferredLanguage.Length > 10)
        {
            return Invalid("preferred_language must not exceed 10 characters.");
        }

        var user = await _userRepository.GetByIdAsync(userId);
        if (user is null)
        {
            return new UpdateProfileOutcome(ProfileUpdateResult.NotFound, null, null, null);
        }

        // Stale JWT Claim (this class's headline concept): a JWT is
        // immutable once signed. Capturing the PRE-update values here is
        // what lets us tell, after saving, whether a claim actually baked
        // into the token (gender/status) changed value — a name-only PATCH
        // must not pay for a token reissue it doesn't need.
        var previousGender = user.Gender;
        var previousStatus = user.Status;

        if (request.Name is not null)
        {
            user.Name = request.Name;
        }

        if (request.PreferredLanguage is not null)
        {
            user.PreferredLanguage = request.PreferredLanguage;
        }

        if (normalizedGender is not null)
        {
            user.Gender = normalizedGender;
        }

        if (request.Status is not null)
        {
            // Stored lower-case (AllowedStatuses' "looking"/"unavailable"),
            // rather than Gender's PascalCase convention — each field keeps
            // whatever casing its own prior tickets already established.
            user.Status = request.Status.ToLowerInvariant();
        }

        // Chained Condition Across Async Steps (bug fix — see User.
        // TryCompleteOnboarding's comment): a PATCH that sets the LAST
        // missing profile field (commonly gender, since Name/PreferredLanguage
        // are usually filled at sign-in and PhotoUrl by a separate photo
        // upload) is exactly as likely to be the step that completes
        // onboarding as OTP verification is — this call must make the same
        // check OtpService.VerifyOtpAsync makes, not assume phone
        // verification always finishes last.
        var justActivated = user.TryCompleteOnboarding();

        try
        {
            await _userRepository.UpdateAsync(user);
        }
        catch (DbUpdateConcurrencyException)
        {
            // Optimistic Concurrency (see User.RowVersion): two concurrent
            // writes (e.g. PATCH /users/me racing POST /users/me/photo) can
            // both read this row before either writes it back. The second
            // UPDATE's stale RowVersion doesn't match the current one, so EF
            // Core throws here instead of silently overwriting the first
            // write's changes.
            return new UpdateProfileOutcome(
                ProfileUpdateResult.Conflict,
                "User profile was modified concurrently. Please fetch the latest version and retry.",
                null,
                null);
        }

        // Event-Driven Architecture (T020, same rule as OtpService.
        // VerifyOtpAsync): publish only on the call that actually flipped
        // IsOnboardingComplete false -> true, and only after the save above
        // succeeded — a retried/concurrent PATCH that finds it already true
        // must never re-announce a fact Discovery Service already reacted to.
        if (justActivated)
        {
            _userVerifiedEventPublisher.PublishUserVerified(user, DateTime.UtcNow);
        }

        // Reissue only when a claim actually baked into the access token
        // changed — see IJwtIssuer.IssueAccessToken's "sub"/"role"/"gender"/
        // "status" claim set, plus "onboarding_complete" (same claim
        // OtpService.VerifyOtpAsync reissues for, now that either call can be
        // the one that flips it). Called AFTER SaveChangesAsync (via
        // UpdateAsync above), against the freshly-saved `user`: issuing from
        // a pre-save copy would risk claims that don't match what's
        // actually persisted if a concurrent write raced this one.
        string? newAccessToken = null;
        var genderChanged = !string.Equals(previousGender, user.Gender, StringComparison.Ordinal);
        var statusChanged = !string.Equals(previousStatus, user.Status, StringComparison.Ordinal);
        if (genderChanged || statusChanged || justActivated)
        {
            newAccessToken = _jwtIssuer.IssueAccessToken(user);
        }

        return new UpdateProfileOutcome(ProfileUpdateResult.Success, null, UserProfileDto.FromUser(user), newAccessToken);
    }

    public async Task<PhotoUploadOutcome> UploadPhotoAsync(long userId, IFormFile? photo, string requestBaseUrl, CancellationToken cancellationToken)
    {
        // Guard Clause (project convention): reject an obviously-unusable
        // upload before ever touching disk — no wasted I/O on a request
        // that can't possibly succeed.
        if (photo is null || photo.Length == 0)
        {
            return InvalidPhoto("A photo file (multipart field \"photo\") is required.");
        }

        if (photo.Length > _photoOptions.MaxSizeBytes)
        {
            return InvalidPhoto($"Photo must be {_photoOptions.MaxSizeBytes / 1_000_000} MB or smaller.");
        }

        if (string.IsNullOrEmpty(photo.ContentType) || !photo.ContentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
        {
            return InvalidPhoto("Uploaded file must be an image.");
        }

        if (photo.ContentType.Equals("image/svg+xml", StringComparison.OrdinalIgnoreCase))
        {
            return InvalidPhoto("SVG images are not allowed. Please upload a JPEG or PNG.");
        }

        var user = await _userRepository.GetByIdAsync(userId);
        if (user is null)
        {
            return new PhotoUploadOutcome(PhotoUploadResult.NotFound, null, null, null);
        }

        var photoUrl = await _photoStorageService.SavePhotoAsync(userId, photo, requestBaseUrl, cancellationToken);

        // Overwrites any Google-provided photo_url unconditionally (T018
        // acceptance criterion) — a freshly-uploaded photo is always more
        // trustworthy than whatever Google handed back at sign-in, since the
        // user chose it deliberately just now.
        user.PhotoUrl = photoUrl;

        // Chained Condition Across Async Steps (bug fix — see User.
        // TryCompleteOnboarding's comment): PhotoUrl is one of the four
        // fields IsProfileComplete() requires, so this upload — not just a
        // PATCH or OTP verification — can just as easily be the step that
        // completes onboarding, depending on the order the client calls
        // these three endpoints in.
        var justActivated = user.TryCompleteOnboarding();

        try
        {
            await _userRepository.UpdateAsync(user);
        }
        catch (DbUpdateConcurrencyException)
        {
            // Optimistic Concurrency — same reasoning as UpdateProfileAsync's
            // catch above.
            return new PhotoUploadOutcome(
                PhotoUploadResult.Conflict,
                "User profile was modified concurrently. Please fetch the latest version and retry.",
                null,
                null);
        }

        // Event-Driven Architecture (T020) — same rule as OtpService.
        // VerifyOtpAsync/UpdateProfileAsync above: publish only on the call
        // that actually flipped IsOnboardingComplete, and only after the save
        // succeeded.
        if (justActivated)
        {
            _userVerifiedEventPublisher.PublishUserVerified(user, DateTime.UtcNow);
        }

        // Stale JWT Claim (same reasoning as UpdateProfileAsync's reissue
        // check): "onboarding_complete" is baked into the access token
        // (JwtIssuer.cs) same as gender/status — a photo upload that just
        // flipped it must refresh the caller's token too, not just PATCH.
        var newAccessToken = justActivated ? _jwtIssuer.IssueAccessToken(user) : null;

        return new PhotoUploadOutcome(PhotoUploadResult.Success, null, UserProfileDto.FromUser(user), newAccessToken);
    }

    public async Task<PublicUserProfileDto?> GetPublicProfileAsync(long id)
    {
        var user = await _userRepository.GetByIdAsync(id);

        // Privacy-critical (do not "simplify" this to UserProfileDto with
        // some fields nulled out) — see PublicUserProfileDto's class comment
        // for the full reasoning.
        return user is null ? null : PublicUserProfileDto.FromUser(user);
    }

    private static UpdateProfileOutcome Invalid(string message) =>
        new(ProfileUpdateResult.InvalidRequest, message, null, null);

    private static PhotoUploadOutcome InvalidPhoto(string message) =>
        new(PhotoUploadResult.InvalidRequest, message, null, null);
}
