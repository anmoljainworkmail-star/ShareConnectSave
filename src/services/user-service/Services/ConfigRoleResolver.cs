namespace user_service.Services;

using Microsoft.Extensions.Options;
using user_service.Configuration;
using user_service.Models;
using user_service.Services.Interfaces;

// Strategy implementation backed by the Admin__UserIds environment variable.
// See AdminRoleOptions for why this is config today and a column later.
public class ConfigRoleResolver : IRoleResolver
{
    public const string AdminRole = "admin";
    public const string UserRole = "user";

    private readonly HashSet<long> _adminIds;

    public ConfigRoleResolver(IOptions<AdminRoleOptions> options, ILogger<ConfigRoleResolver> logger)
    {
        // Parsed once at construction (registered Singleton, like JwtIssuer):
        // the allowlist is read on every token issued, so re-splitting a
        // string per login would be pointless work.
        _adminIds = Parse(options.Value.UserIds, logger);
    }

    public string ResolveRole(User user) =>
        _adminIds.Contains(user.Id) ? AdminRole : UserRole;

    private static HashSet<long> Parse(string? raw, ILogger logger)
    {
        var ids = new HashSet<long>();

        // Guard clause: unset/blank means "no admins", not an error.
        if (string.IsNullOrWhiteSpace(raw))
        {
            return ids;
        }

        foreach (var part in raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            // Fail closed: a typo must never silently grant or hide a role.
            // A malformed entry is skipped and logged loudly so the operator
            // sees it at startup, rather than discovering it as "why can't I
            // sign in as admin". Only the offending token is logged - it is
            // operator-supplied config, not user data.
            if (!long.TryParse(part, out var id))
            {
                logger.LogWarning("Admin:UserIds contains a non-numeric entry '{Entry}' - ignored.", part);
                continue;
            }

            ids.Add(id);
        }

        return ids;
    }
}
