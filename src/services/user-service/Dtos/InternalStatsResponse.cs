namespace user_service.Dtos;

using System.Text.Json.Serialization;

// T105: Admin Dashboard platform-health metric — a plain aggregate, not a
// per-user projection like InternalAccountStatusResponse above. Two
// Different Axes Query Logic: AccountStatus (account standing — Active/
// Suspended) and IsOnboardingComplete (identity/profile verification
// progress) are independent dimensions on the SAME row — a user can be
// Active but still mid-onboarding, or fully onboarded and later suspended.
// "Active in circulation" for this metric only holds when BOTH axes agree
// (not suspended AND fully onboarded); collapsing that into a single status
// field would repeat the exact mistake the Status/IsOnboardingComplete split
// on User.cs already fixed once on this entity.
public record InternalStatsResponse(
    [property: JsonPropertyName("total_active_users")] int TotalActiveUsers);
