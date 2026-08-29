using Microsoft.EntityFrameworkCore;
using user_service.Infrastructure;
using user_service.Repositories;
using user_service.Repositories.Interfaces;

var builder = WebApplication.CreateBuilder(args);

// No Hardcoded Config: the connection string never appears as a literal here
// or in appsettings.json. builder.Configuration.GetConnectionString("UserDb")
// resolves to configuration key "ConnectionStrings:UserDb", which ASP.NET
// Core's environment-variable provider binds from an env var literally named
// ConnectionStrings__UserDb (double underscore is the documented separator
// for nested keys). Same env var name AppDbContextFactory already reads for
// `dotnet ef` — one source of truth for both design-time and run-time.
builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseSqlServer(builder.Configuration.GetConnectionString("UserDb")
        ?? throw new InvalidOperationException("ConnectionStrings__UserDb is not set")));

// Dependency Inversion (SOLID D): endpoints (T016+) will ask the DI container
// for IUserRepository/IOtpRepository/IIdentityVerificationRepository, never
// for the concrete EF classes below — this is the one place that binds the
// abstraction to today's SQL Server implementation.
builder.Services.AddScoped<IUserRepository, UserRepository>();
builder.Services.AddScoped<IOtpRepository, OtpRepository>();
builder.Services.AddScoped<IIdentityVerificationRepository, IdentityVerificationRepository>();

var app = builder.Build();

// Health Endpoint as a Dependency Gate: liveness only, same shape as the API
// Gateway's /health from T014. Docker Compose's healthcheck for this service
// polls this route, and every later service that depends_on user-service
// with condition: service_healthy relies on this being a real "the process is
// up" signal. Deliberately no DB ping here — checking downstream
// dependencies is a different concern (readiness, not liveness) and is out
// of scope for this ticket.
app.MapGet("/health", () => Results.Ok());

app.Run();
