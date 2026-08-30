namespace user_service.Models;

// Database per Service: this table lives only in UserServiceDb. Discovery,
// Connection, and every other service that needs "does this user exist / are
// they verified" gets that answer over HTTP (or a Kafka event like
// user.verified), never via a cross-database join. That constraint is what
// lets User Service change its schema or even its DB engine without any
// other service noticing.
public class User
{
    // Primary Keys: BIGINT IDENTITY over UUID/GUID. This is a single-database
    // record (no multi-writer replication, no need to generate an ID offline
    // before an INSERT), so a narrow, sequential, clustered-index-friendly
    // integer beats a 16-byte UUID that fragments the clustered index and
    // bloats every FK and non-clustered index that references it.
    public long Id { get; set; }

    public string GoogleId { get; set; } = string.Empty;

    // Added in T016: Google's tokeninfo response hands this back on every
    // sign-in, but it is never re-validated or re-used for identity - only
    // GoogleId (Google's "sub") is the lookup key. Email is stored purely as
    // profile data (e.g. future notification/support use), matching the
    // ticket's "extract sub, email, name, picture" requirement.
    public string? Email { get; set; }

    public string? Phone { get; set; }

    public string Name { get; set; } = string.Empty;

    public string? PhotoUrl { get; set; }

    public string Gender { get; set; } = "Unspecified";

    public string PreferredLanguage { get; set; } = "en";

    public string Status { get; set; } = "Unavailable";

    // Temporal Columns: always DateTime in UTC, never local time. A traveler
    // in one timezone and a server in another must agree on a single instant
    // for "when was this created" — mixing local times silently corrupts any
    // later duration math (OTP expiry, trust score decay, etc.).
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}
