namespace user_service.Dtos;

using System.Text.Json.Serialization;

// T049: deliberately the ONLY field on this request — there is no "user_id"
// or "target_id" property anywhere on this type. That is a structural
// guarantee, same discipline as PublicUserProfileDto's missing Gender/Phone
// fields (see that file's comment): a caller physically cannot supply
// someone else's id in this body, because the type has no slot for one. The
// caller's identity comes exclusively from the gateway-injected X-User-Id
// header (see UserProfileController.UpdateMyFcmToken), the same convention
// every other /users/me action already follows.
public record UpdateFcmTokenRequest(
    [property: JsonPropertyName("fcm_token")] string? FcmToken);
