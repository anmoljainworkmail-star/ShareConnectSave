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

    // T017: set the instant phone ownership was proven via OTP. Null means
    // "never verified". Together with IsProfileComplete() below, this is
    // one of the two conditions UserOnboardingSaga (see
    // .claude/skills/saga.md) checks before status can advance to "active".
    // No saga_state table is needed for this saga — per that skill file,
    // the users table's own columns ARE the saga state.
    public DateTime? PhoneVerifiedAt { get; set; }

    public string Name { get; set; } = string.Empty;

    public string? PhotoUrl { get; set; }

    public string Gender { get; set; } = "Unspecified";

    public string PreferredLanguage { get; set; } = "en";

    // Post-T018 fix: this column ONLY ever holds the user-owned availability
    // toggle ("looking" | "unavailable" — see UserProfileService's
    // AllowedStatuses) once a profile exists. It used to double as the
    // UserOnboardingSaga's own state ("incomplete" -> "active", set by
    // OtpService) until PATCH /users/me writing "looking"/"unavailable" into
    // this SAME column was found to silently clobber that saga signal the
    // very first time a user touched their availability toggle — the two
    // concerns needed separate storage. See IsOnboardingComplete below for
    // where that signal actually lives now.
    public string Status { get; set; } = "Unavailable";

    // Temporal Columns: always DateTime in UTC, never local time. A traveler
    // in one timezone and a server in another must agree on a single instant
    // for "when was this created" — mixing local times silently corrupts any
    // later duration math (OTP expiry, trust score decay, etc.).
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    // Saga Compensation / chained condition (T017's "Patterns demonstrated"):
    // advances to true only once EVERY UserOnboardingSaga condition holds —
    // today that's "phone verified" AND "profile complete"; T019 (identity
    // verification) will add a third condition the same way. Keeping the
    // rule here, on the entity, instead of duplicated inline in OtpService
    // (and later T019's own consumer) means every caller checks the same
    // definition of "complete" — a future field added to the rule has
    // exactly one place to change.
    public bool IsProfileComplete() =>
        !string.IsNullOrWhiteSpace(Name) &&
        !string.IsNullOrWhiteSpace(PhotoUrl) &&
        !string.IsNullOrWhiteSpace(PreferredLanguage) &&
        !string.Equals(Gender, "Unspecified", StringComparison.Ordinal);

    // Post-T018 fix: the UserOnboardingSaga's own state, split out of Status
    // (see that property's comment for why). OtpService.VerifyOtpAsync is
    // the only writer that ever flips this to true, and only once
    // IsProfileComplete() holds — a brand-new user must default to false,
    // never anything that looks "ready", the same guarantee AuthController's
    // GoogleSignIn relies on for a first-time sign-in.
    public bool IsOnboardingComplete { get; set; }

    // Optimistic Concurrency Token: two concurrent writes to the same user row
    // (e.g. PATCH /users/me racing POST /users/me/photo, or a double-submitted
    // PATCH) can both read this row before either writes it back. Without a
    // version stamp, the second UPDATE silently overwrites the first's changes
    // — even fields the later write never touched. SQL Server's `rowversion`
    // type auto-increments on every write to this row; EF Core includes it in
    // every UPDATE's WHERE clause, so a stale write throws DbUpdateConcurrencyException
    // instead of succeeding silently (see AppDbContext's IsRowVersion() config
    // and UserProfileController's catch/409 around it).
    public byte[]? RowVersion { get; set; }
}
