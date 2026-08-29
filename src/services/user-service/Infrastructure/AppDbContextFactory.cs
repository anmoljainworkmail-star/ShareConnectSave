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
//
// T015 note — keeping Flyway (Java services) and EF Core (.NET services) in
// sync: the two migration tools do not, and will never, share migration
// files or a migration-history table. Each service is the sole source of
// truth for its own database (Database per Service) — User Service's schema
// lives only in this project's EF Core migrations under Migrations/, and
// nothing outside this service is allowed to alter UserServiceDb directly.
// Cross-service schema drift (e.g. "does Discovery Service's cached view of
// a user's columns still match what User Service actually stores") is
// prevented by process, not tooling: SPECS.md/REQUIREMENTS.md is the shared
// contract both a Flyway script and an EF Core migration are reviewed
// against at PR time, and any cross-service field a consumer needs is
// exposed over HTTP/Kafka (see the user.verified event), never read from
// the other service's tables. There is deliberately no automated schema-sync
// step between Flyway and EF Core — that would require a shared migration
// runner, which would violate Database per Service by creating a second
// piece of code with write access to every service's schema.
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
