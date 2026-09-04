namespace user_service.Dtos;

using System.Text.Json.Serialization;
using user_service.Models;

// Privacy-by-construction / data minimization (this ticket's core lesson):
// this type structurally has no Gender or Phone property. There is no
// "if (isPublicCaller) dto.Gender = null" branch anywhere for a future edit
// to accidentally delete — the absence of the field in the TYPE is the
// safety guarantee, not a runtime check that only works as long as everyone
// remembers it. GET /users/:id can only ever return what this record can
// hold, no matter how UserProfileDto or the User entity change later.
//
// rating/destination (the ticket's literal field list for this endpoint) are
// intentionally NOT included yet: per CLAUDE.md's Database-per-Service rule,
// those live in Rating Service's and Discovery Service's own databases —
// neither service has any code yet (Discovery starts at T020, Rating is
// Phase 7 per SPECS.md). user-service can only project fields it actually
// owns; it must never reach into another service's database to fill these in
// itself. Once those services exist, a composition layer (the gateway, or a
// client issuing parallel calls) is what assembles the full discovery card
// from each service's own public projection.
//
// identity_badge (T019) IS included here, unlike rating/destination: it is
// genuinely owned data — a column on THIS service's own users table, not
// borrowed from another service's database — and it is the one piece of
// trust signal this ticket exists to make available to Discovery Service
// (T020) and anyone else deciding whether to show the "Verified" badge for
// someone else's card. Unlike gender/phone, it carries no privacy risk by
// design: knowing a stranger is identity-verified is the whole point of the
// badge.
public record PublicUserProfileDto(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("photo_url")] string? PhotoUrl,
    [property: JsonPropertyName("identity_badge")] bool IdentityBadge)
{
    public static PublicUserProfileDto FromUser(User user) => new(
        user.Id,
        user.Name,
        user.PhotoUrl,
        user.IdentityBadge);
}
