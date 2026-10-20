namespace notification_service.Clients;

using System.Net.Http.Json;
using System.Text.Json.Serialization;

// KNOWN GAP (call this out, do not silently paper over it - T047 scope is
// notification-service only):
// As of T047, User Service's public-profile surface (GetPublicProfile ->
// PublicUserProfileDto, see src/services/user-service/Controllers/
// UserProfileController.cs and Dtos/PublicUserProfileDto.cs) has NO fcm
// device token field anywhere - not on the User entity, not in any DTO. No
// endpoint in User Service exists whose job is "give me this user's device
// token" today.
//
// GET /users/{id} is still the closest existing endpoint SHAPE for this
// lookup - "fetch data about some OTHER user by id", exactly what a
// device-token lookup needs (as opposed to GET /users/me, which only ever
// resolves the CALLER's own identity from a JWT-derived header and can't
// take an arbitrary target userId at all). So this client calls that
// endpoint and reads a "fcm_token" property that User Service does not
// serialize yet. Until a future ticket adds that column/field to User
// Service, UserLookupResponse.FcmToken always deserializes to null here,
// and FcmNotificationService.SendAsync no-ops accordingly - this is a
// structurally complete call wired to a real endpoint, not a stub, but it
// cannot deliver a real push until that follow-up ticket lands.
public class UserServiceClient(HttpClient httpClient) : IUserServiceClient
{
    public async Task<string?> GetFcmDeviceTokenAsync(long userId, CancellationToken cancellationToken = default)
    {
        using var response = await httpClient.GetAsync($"/users/{userId}", cancellationToken);

        // Guard clause over nested ifs (CLAUDE.md rule): an unknown user or
        // an unreachable User Service both collapse to "no token available"
        // here - the caller does not need to distinguish them to decide
        // whether a push can be sent.
        if (!response.IsSuccessStatusCode)
        {
            return null;
        }

        var profile = await response.Content.ReadFromJsonAsync<UserLookupResponse>(cancellationToken: cancellationToken);
        return profile?.FcmToken;
    }

    // A client-local response shape, not user-service's PublicUserProfileDto
    // reused: a caller of an HTTP dependency should define the shape IT
    // expects back, never bind directly to the producer's internal DTO type.
    // That keeps notification-service's compile-time contract decoupled from
    // whatever user-service's DTO happens to look like today - the same
    // discipline a hand-rolled response record for a Spring WebClient/Feign
    // call would apply on the Java side.
    private sealed record UserLookupResponse(
        [property: JsonPropertyName("id")] long Id,
        [property: JsonPropertyName("fcm_token")] string? FcmToken);
}
