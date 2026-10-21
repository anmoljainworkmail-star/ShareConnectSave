namespace notification_service.Clients;

using System.Net.Http.Json;
using System.Text.Json.Serialization;

// T049: Calls User Service's INTERNAL-ONLY endpoint GET /users/internal/{id}/fcm-token
// (excluded from YARP gateway routing, reachable only via direct inter-service HTTP
// calls inside the Docker network) rather than the public GET /users/{id} endpoint.
// This keeps device tokens out of the discovery card API that any authenticated user
// can query for any other user's id.
//
// Even though both endpoints query by user id, they serve different concerns:
// - GET /users/{id} (public, gateway-routed): returns discovery-card data visible
//   to all authenticated clients, structured as PublicUserProfileDto
// - GET /users/internal/{id}/fcm-token (internal, backend-only): returns device
//   tokens for Notification Service's dispatch decision, reachable only by
//   Notification Service calling http://user-service:8080 directly
//
// A client-local response shape, not user-service's DTO reused: a caller of an HTTP
// dependency should define the shape IT expects back, never bind directly to the
// producer's internal DTO type. That keeps notification-service's compile-time
// contract decoupled from whatever user-service's internal structure looks like today.
public class UserServiceClient(HttpClient httpClient, IConfiguration config) : IUserServiceClient
{
    public async Task<string?> GetFcmDeviceTokenAsync(long userId, CancellationToken cancellationToken = default)
    {
        // Defense in depth (security review fix, cycle 2): attach the internal
        // service key header to every call, so the endpoint can verify this call
        // came from an authorized internal service, not an external/compromised
        // client. The key is read from IConfiguration (set via env vars in
        // docker-compose.override.yml — see INTERNAL_SERVICE_KEY). This matches
        // user-service's GetInternalFcmToken's own X-Internal-Service-Key check.
        var internalServiceKey = config["InternalServiceKey"];
        using var request = new HttpRequestMessage(HttpMethod.Get, $"/users/internal/{userId}/fcm-token");
        if (!string.IsNullOrEmpty(internalServiceKey))
        {
            request.Headers.Add("X-Internal-Service-Key", internalServiceKey);
        }

        using var response = await httpClient.SendAsync(request, cancellationToken);

        // Guard clause over nested ifs (CLAUDE.md rule): an unknown user or
        // an unreachable User Service both collapse to "no token available"
        // here - the caller does not need to distinguish them to decide
        // whether a push can be sent.
        if (!response.IsSuccessStatusCode)
        {
            return null;
        }

        var token = await response.Content.ReadFromJsonAsync<InternalFcmTokenResponse>(cancellationToken: cancellationToken);
        return token?.FcmToken;
    }

    // A response DTO matching User Service's InternalFcmTokenResponse shape.
    // See this class's file comment for why this is defined locally rather than
    // reusing user-service's DTO directly.
    private sealed record InternalFcmTokenResponse(
        [property: JsonPropertyName("fcm_token")] string? FcmToken);
}
