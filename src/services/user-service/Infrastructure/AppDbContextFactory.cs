using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace user_service.Infrastructure;

// Design-Time Factory: `dotnet ef migrations add` runs outside the app's own
// Program.cs startup (no ASP.NET host, no DI container spun up), so EF Core
// needs a separate, explicit way to construct an AppDbContext just to inspect
// the model and diff it against the last migration. This class exists only
// for that CLI-time codegen step — it is never touched during `dotnet run`.
//
// No Hardcoded Config: the connection string is read from the same
// ConnectionStrings__UserDb env var the running service uses (see
// docker-compose.override.yml / .env.example), never a literal string here.
// Design-time tooling that needs *a* value to shape the SQL still must not
// smuggle in a real credential.
public class AppDbContextFactory : IDesignTimeDbContextFactory<AppDbContext>
{
    public AppDbContext CreateDbContext(string[] args)
    {
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__UserDb")
            ?? throw new InvalidOperationException(
                "ConnectionStrings__UserDb must be set (e.g. from .env) before running " +
                "'dotnet ef' commands — no connection string is hardcoded here by design.");

        var optionsBuilder = new DbContextOptionsBuilder<AppDbContext>();
        optionsBuilder.UseSqlServer(connectionString);

        return new AppDbContext(optionsBuilder.Options);
    }
}
