namespace user_service.Dtos;

using System.Text.Json.Serialization;

// PATCH request body: every field is nullable/optional by design — PATCH
// semantics mean "apply only the fields present in this request", unlike
// PUT's "replace the whole resource". UserProfileController treats a null
// field as "not provided, leave unchanged" and validates every field that
// IS provided before applying any of them, so a partially-invalid request
// never partially mutates the row.
public record UpdateProfileRequest(
    [property: JsonPropertyName("name")] string? Name,
    [property: JsonPropertyName("preferred_language")] string? PreferredLanguage,
    [property: JsonPropertyName("gender")] string? Gender,
    [property: JsonPropertyName("status")] string? Status);

// Response envelope for PATCH /users/me only. AccessToken is omitted from
// the JSON entirely (not just null) when name/preferred_language were the
// only fields that changed — its presence on the wire IS the signal to the
// frontend "swap your stored token for this one", so a silently-present
// `"access_token": null` would be a worse contract than the field simply
// not existing.
public record UpdateProfileResponse(
    [property: JsonPropertyName("profile")] UserProfileDto Profile,
    [property: JsonPropertyName("access_token")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    string? AccessToken);
