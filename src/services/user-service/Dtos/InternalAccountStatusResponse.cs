namespace user_service.Dtos;

using System.Text.Json.Serialization;
using user_service.Models;

// T102: API Contract Adherence (this ticket's headline lesson) — a contract
// is bidirectional. admin-service's UserServiceClientImpl ALREADY declared
// what it expects on the wire (user_id/display_name/account_status/
// suspended_at, snake_case — see its nested UserProfileDto/SuspendedUserDto
// records) before this endpoint existed; this type's shape is dictated by
// that caller, not by whatever would be convenient to name on this side.
//
// One shared DTO for all three internal endpoints (list/get/update) rather
// than three near-identical records: admin-service's own SuspendedUserDto
// doesn't declare an account_status field, but its
// @JsonIgnoreProperties(ignoreUnknown = true) annotation means the extra
// field this DTO sends on the list endpoint is simply ignored, not a
// deserialization error — so one response shape safely serves all three
// call sites without admin-service needing three different wire contracts
// for what is, underneath, the same "account state" fact.
//
// Deliberately excluded from PublicUserProfileDto/UserProfileDto (see their
// own class comments) — account standing is admin-only data, never exposed
// to peer-discovery clients through the public /users/{id} endpoint.
public record InternalAccountStatusResponse(
    [property: JsonPropertyName("user_id")] long UserId,
    [property: JsonPropertyName("display_name")] string DisplayName,
    [property: JsonPropertyName("account_status")] string AccountStatus,
    [property: JsonPropertyName("suspended_at")] DateTime? SuspendedAt)
{
    public static InternalAccountStatusResponse FromUser(User user) => new(
        user.Id,
        user.Name,
        user.AccountStatus,
        user.SuspendedAt);
}

// T102: internal-only request body for PUT /users/internal/{id}/account-status
// — mirrors admin-service's own AccountStatusUpdateRequest wire shape
// (account_status, snake_case) exactly, same bidirectional-contract
// reasoning as the response DTO above. Nullable: AdminAccountService (not
// this record) owns the "was this value actually valid" guard clause, the
// same split UpdateProfileRequest/UserProfileService already use for
// gender/status.
public record UpdateAccountStatusRequest(
    [property: JsonPropertyName("account_status")] string? AccountStatus);
