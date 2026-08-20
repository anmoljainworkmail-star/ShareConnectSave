namespace user_service.Models;

// Verification is modeled as its own table rather than extra columns bolted
// onto User. Single Responsibility at the schema level: `users` answers "who
// is this person", `identity_verifications` answers "has this person proven
// who they say they are, and when". Keeping them separate means the
// verification workflow (photo upload, review, re-verification) can evolve —
// e.g. multiple attempts per user — without reshaping the users table.
public class IdentityVerification
{
    public long Id { get; set; }

    // Foreign Key: every user-scoped table gets a non-clustered index on the
    // FK column, because the dominant query shape in a per-service DB is
    // "find records belonging to this user", not "scan everything".
    public long UserId { get; set; }

    public User? User { get; set; }

    public string Status { get; set; } = "Pending";

    public DateTime? VerifiedAt { get; set; }
}
