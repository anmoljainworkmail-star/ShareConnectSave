namespace user_service.Dtos;

using System.Text.Json.Serialization;
using user_service.Models;

// Interface Segregation (SOLID I): this is the OWNER-ONLY profile shape —
// every field the signed-in user is entitled to see about themselves,
// including gender and phone. It is never returned from GET /users/:id (see
// PublicUserProfileDto) — two separate types, not one type with fields
// hidden by convention, is what makes that boundary a compile-time fact
// instead of a runtime habit a future edit could forget.
public record UserProfileDto(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("email")] string? Email,
    [property: JsonPropertyName("phone")] string? Phone,
    [property: JsonPropertyName("photo_url")] string? PhotoUrl,
    [property: JsonPropertyName("gender")] string Gender,
    [property: JsonPropertyName("preferred_language")] string PreferredLanguage,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("phone_verified")] bool PhoneVerified,
    [property: JsonPropertyName("onboarding_complete")] bool OnboardingComplete,
    // T019: the "Verified" badge - see User.IdentityBadge's class comment.
    [property: JsonPropertyName("identity_badge")] bool IdentityBadge,
    [property: JsonPropertyName("created_at")] DateTime CreatedAt)
{
    // Explicit field-by-field projection, not "serialize the entity as-is":
    // this mapping is the one place that decides what "my own profile" means
    // on the wire. Even though every User field is safe to expose here, an
    // explicit projection is what keeps this DTO and the entity from
    // silently drifting apart as User grows fields in later tickets — the
    // same discipline PublicUserProfileDto.FromUser uses, just with a wider
    // allow-list.
    public static UserProfileDto FromUser(User user) => new(
        user.Id,
        user.Name,
        user.Email,
        user.Phone,
        user.PhotoUrl,
        user.Gender,
        user.PreferredLanguage,
        user.Status,
        user.PhoneVerifiedAt is not null,
        user.IsOnboardingComplete,
        user.IdentityBadge,
        user.CreatedAt);
}
