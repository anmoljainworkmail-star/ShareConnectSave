namespace user_service.Services.Interfaces;

using user_service.Models;

// Strategy (GoF): "how do we decide a user's role?" is an algorithm that will
// change (today: env allowlist; later: a Role column). Plain English: a
// building's front desk asks "is this person staff or a visitor?" - it should
// not care whether the answer comes from a printed list or a database, only
// that it gets an answer. JwtIssuer depends on this interface, so changing
// the source of truth never touches token-signing code.
//
// Single Responsibility (SOLID S): this decides a role string. It does not
// sign tokens and does not load users.
public interface IRoleResolver
{
    // Returns the value for the JWT "role" claim. api-gateway forwards it as
    // X-User-Role and admin-service requires the exact, case-sensitive
    // "admin" - anything else must be "user".
    string ResolveRole(User user);
}
