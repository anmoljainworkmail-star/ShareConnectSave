namespace user_service.Dtos;

using System.Text.Json.Serialization;

// Internal-only response DTO for GET /users/internal/{id}/fcm-token — reachable
// only via direct inter-service calls (notification-service calling
// http://user-service:8080 directly), never through the YARP gateway.
// This separates the concern of "device token lookup by id, for internal
// notification dispatch" from "public profile data by id, for discovery
// cards visible to any authenticated user" — even though both query by user id,
// the first is private infrastructure (backend-only), the second is user-facing
// API (gateway-routed, publicly accessible to all authenticated users).
public record InternalFcmTokenResponse(
    [property: JsonPropertyName("fcm_token")] string? FcmToken);
