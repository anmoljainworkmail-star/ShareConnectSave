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

// Global Exception Handling (fix): every controller action already returns
// the project's {code, message, traceId} error envelope explicitly for
// EXPECTED failures (bad input, wrong OTP, lockout). GlobalExceptionHandler
// (Infrastructure/GlobalExceptionHandler.cs) is the catch-all for UNEXPECTED
// ones — an unhandled exception anywhere in the pipeline. Without it,
// ASP.NET Core's default behaviour is either the developer-exception-page
// (which can leak internals like SQL parameter values — e.g. a phone
// number — straight into the response in Development) or a bare empty 500
// in Production, neither of which matches the shape every other error
// response in this service already promises. IExceptionHandler (rather than
// AddProblemDetails' RFC 7807 shape) is what lets this handler write the
// SAME ErrorResponse record every other error path already uses.
builder.Services.AddExceptionHandler<GlobalExceptionHandler>();

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

// T017: same Dependency Inversion pattern as JwtIssuerOptions above -
// OtpService depends on IOptions<OtpOptions>, never on IConfiguration or a
// literal lockout-minutes constant.
builder.Services.Configure<OtpOptions>(builder.Configuration.GetSection(OtpOptions.SectionName));

// Typed HttpClient (T016): lets the framework own HttpClient pooling/DNS-refresh
// lifetime for calls to Google's tokeninfo endpoint, instead of GoogleTokenValidator
// holding a single long-lived HttpClient itself - same reasoning as api-gateway's
// AddHttpClient<IJwksService, JwksService>() in T012.
builder.Services.AddHttpClient<IGoogleTokenValidator, GoogleTokenValidator>();

// Typed HttpClient (T017): same pooling/DNS-refresh reasoning as
// IGoogleTokenValidator above - a single call to Twilio's REST API, no SDK
// package added (see TwilioClient's class comment for why).
builder.Services.AddHttpClient<ITwilioClient, TwilioClient>();

// Dependency Inversion (D in SOLID): OtpController asks the DI container for
// IOtpService, never for the concrete OtpService class.
builder.Services.AddScoped<IOtpService, OtpService>();

// Singleton (via DI container, classic pattern from CLAUDE.md's pattern table):
// exactly one RSA keypair for this process's lifetime - see JwtIssuer's own
// comment for why Scoped/Transient here would silently break gateway-side
// JWKS caching.
builder.Services.AddSingleton<IJwtIssuer, JwtIssuer>();

var app = builder.Build();

// Registered as early as possible in the pipeline so it wraps every
// downstream middleware/controller action — see the AddExceptionHandler
// registration above for why this exists. No options object here: passing
// one (e.g. UseExceptionHandler(new ExceptionHandlerOptions { ... })) is what
// wires in the framework's ProblemDetails fallback path — the whole point of
// GlobalExceptionHandler is that it is the one and only thing that runs.
app.UseExceptionHandler();
app.UseStatusCodePages();

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
