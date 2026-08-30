using Microsoft.EntityFrameworkCore;
using user_service.Configuration;
using user_service.Infrastructure;
using user_service.Repositories;
using user_service.Repositories.Interfaces;
using user_service.Services;
using user_service.Services.Interfaces;

var builder = WebApplication.CreateBuilder(args);

// MVC Controllers (switched from Minimal API endpoint-mapping): AddControllers()
// registers ASP.NET Core's controller discovery + action invocation pipeline -
// this is the .NET equivalent of Spring Boot's component scan picking up
// @RestController classes, just explicit instead of classpath scanning.
builder.Services.AddControllers();

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

// Dependency Inversion (D in SOLID): bind the "Jwt" section once, here, into
// a typed JwtIssuerOptions - JwtIssuer then depends on IOptions<JwtIssuerOptions>,
// never on IConfiguration directly. Mirrors api-gateway's identical pattern
// for its own JwtOptions (T012).
builder.Services.Configure<JwtIssuerOptions>(builder.Configuration.GetSection(JwtIssuerOptions.SectionName));

// Typed HttpClient (T016): lets the framework own HttpClient pooling/DNS-refresh
// lifetime for calls to Google's tokeninfo endpoint, instead of GoogleTokenValidator
// holding a single long-lived HttpClient itself - same reasoning as api-gateway's
// AddHttpClient<IJwksService, JwksService>() in T012.
builder.Services.AddHttpClient<IGoogleTokenValidator, GoogleTokenValidator>();

// Singleton (via DI container, classic pattern from CLAUDE.md's pattern table):
// exactly one RSA keypair for this process's lifetime - see JwtIssuer's own
// comment for why Scoped/Transient here would silently break gateway-side
// JWKS caching.
builder.Services.AddSingleton<IJwtIssuer, JwtIssuer>();

var app = builder.Build();

// MapControllers() wires up attribute-routed controllers (AuthController's
// [HttpPost("/auth/google")] etc.) - Program.cs stays a list of
// pipeline/DI registrations, not a place individual routes get mapped inline,
// same intent as the Minimal API version's app.MapAuthEndpoints() before it.
app.MapControllers();

// Health Endpoint as a Dependency Gate: liveness only, same shape as the API
// Gateway's /health from T014. Docker Compose's healthcheck for this service
// polls this route, and every later service that depends_on user-service
// with condition: service_healthy relies on this being a real "the process is
// up" signal. Deliberately no DB ping here — checking downstream
// dependencies is a different concern (readiness, not liveness) and is out
// of scope for this ticket.
app.MapGet("/health", () => Results.Ok());

app.Run();
