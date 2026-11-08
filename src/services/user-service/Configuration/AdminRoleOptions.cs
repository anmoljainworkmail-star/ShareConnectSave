namespace user_service.Configuration;

// Dependency Inversion (SOLID D): same typed-options pattern as JwtIssuerOptions -
// the role resolver reads this, never IConfiguration or a literal id list.
//
// Why config and not a database column (deliberate, revisit for production):
// a persisted Role column on User is the long-term home for "who is an admin"
// (revocable at runtime, auditable, no redeploy). It also needs an EF
// migration and a way to promote someone. For a dev-stage platform whose only
// admins are the developers, an env-driven allowlist gets a real admin token
// issued today with zero schema change. The seam is IRoleResolver: swapping
// this for a DB-backed resolver later changes one class, not JwtIssuer.
public class AdminRoleOptions
{
    public const string SectionName = "Admin";

    // Comma-separated user-service user ids (the bigint "sub" claim), e.g.
    // "1,7". Keyed on OUR id, never on email: Google's email claim is not
    // stored as verified, so an email allowlist could be satisfied by an
    // attacker who controls an address that merely matches. Our own id is
    // assigned by this service and cannot be chosen by a caller.
    //
    // Arrives via the Admin__UserIds environment variable (see .env.example).
    // Empty by default: with nothing configured, nobody is an admin.
    public string? UserIds { get; set; }
}
