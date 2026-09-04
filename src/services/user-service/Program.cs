using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using user_service.Configuration;
using user_service.Contracts;
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
builder.Services.AddControllers()
    .ConfigureApiBehaviorOptions(opt =>
    {
        // Model Validation Error Envelope (fix): [ApiController] automatically
        // returns ValidationProblemDetails on model binding/validation failure
        // (e.g. malformed JSON). This factory replaces that with this service's
        // standard {code, message, traceId} ErrorResponse shape — AuthController,
        // OtpController, UserProfileController all depend on it for consistent
        // error handling.
        opt.InvalidModelStateResponseFactory = ctx => new BadRequestObjectResult(
            new ErrorResponse("INVALID_REQUEST", "Request body is malformed or missing required fields.", ctx.HttpContext.TraceIdentifier));
    });

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

// Framework requirement (not a behavior change): ASP.NET Core's
// UseExceptionHandler() middleware validates at startup that SOMETHING can
// handle an unhandled exception - either an explicit ExceptionHandlingPath/
// ExceptionHandler, an IProblemDetailsService, or a registered
// IExceptionHandler. AddProblemDetails() registers IProblemDetailsService,
// satisfying that startup check - but GlobalExceptionHandler.TryHandleAsync
// always returns true (it fully writes the response itself and never falls
// through), so IProblemDetailsService's RFC 7807 shape is never actually
// produced by this service. This line exists purely to satisfy the
// middleware's constructor validation, not to change what a client receives.
builder.Services.AddProblemDetails();

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

// T018: same Dependency Inversion pattern again - ProfilePhotoStorageService
// depends on IOptions<ProfilePhotoOptions>, never a literal storage path.
builder.Services.Configure<ProfilePhotoOptions>(builder.Configuration.GetSection(ProfilePhotoOptions.SectionName));

// T019: same Dependency Inversion pattern again - AzureFaceMatchService
// depends on IOptions<AzureFaceOptions>, never reads config keys ad hoc.
// Harmless to bind even in stub mode (IDENTITY_VERIFY_STUB=true) - the
// values are simply never read, since StubFaceMatchService is what gets
// registered as IFaceMatchService below in that case.
builder.Services.Configure<AzureFaceOptions>(builder.Configuration.GetSection(AzureFaceOptions.SectionName));

// Typed HttpClient (T016): lets the framework own HttpClient pooling/DNS-refresh
// lifetime for calls to Google's tokeninfo endpoint, instead of GoogleTokenValidator
// holding a single long-lived HttpClient itself - same reasoning as api-gateway's
// AddHttpClient<IJwksService, JwksService>() in T012.
builder.Services.AddHttpClient<IGoogleTokenValidator, GoogleTokenValidator>();

// Typed HttpClient (T017): same pooling/DNS-refresh reasoning as
// IGoogleTokenValidator above - a single call to Twilio's REST API, no SDK
// package added (see TwilioClient's class comment for why).
builder.Services.AddHttpClient<ITwilioClient, TwilioClient>();

// Typed HttpClient (T019): same pooling/DNS-refresh reasoning as
// IGoogleTokenValidator/ITwilioClient above - IdentityVerificationService
// never touches HttpClient directly, only IBasePhotoDownloadService, to
// fetch whatever photo_url already points at.
builder.Services.AddHttpClient<IBasePhotoDownloadService, BasePhotoDownloadService>();

// Strategy (classic pattern, per CLAUDE.md's pattern table) + Dependency
// Inversion (SOLID D) + Open/Closed (SOLID O): this is the ONE place that
// decides which IFaceMatchService implementation is wired in, selected once
// at startup by the IDENTITY_VERIFY_STUB env flag - never an "if (isDev)"
// branch inside IdentityVerificationService or the controller. Reading a
// flat env var directly here (rather than via an IOptions<T> section) is a
// composition-root concern only, same category as the ProfilePhotoOptions/
// AzureFaceOptions binding above it, not the "ad hoc Environment.GetEnvironmentVariable
// inside a service" pattern this project's config rule warns against - the
// flag never leaves this one line.
var identityVerifyStub = string.Equals(
    builder.Configuration["IDENTITY_VERIFY_STUB"],
    "true",
    StringComparison.OrdinalIgnoreCase);

if (identityVerifyStub)
{
    builder.Services.AddScoped<IFaceMatchService, StubFaceMatchService>();
}
else
{
    builder.Services.AddHttpClient<IFaceMatchService, AzureFaceMatchService>();
}

// Dependency Inversion (D in SOLID): OtpController asks the DI container for
// IOtpService, never for the concrete OtpService class.
builder.Services.AddScoped<IOtpService, OtpService>();

// T018: Dependency Inversion (D in SOLID) + Strategy - UserProfileService
// asks for IProfilePhotoStorageService, never for the concrete local-disk
// class. This is the one line that would change to swap in a real object
// store later (see ProfilePhotoStorageService's class comment).
builder.Services.AddScoped<IProfilePhotoStorageService, ProfilePhotoStorageService>();

// Fix: Dependency Inversion (D in SOLID) - UserProfileController asks for
// IUserProfileService, never AppDbContext/IUserRepository/IJwtIssuer
// directly. Every profile business rule (validation, the reissue-on-claim-
// change decision, photo constraints) lives behind this one interface, the
// same shape IOtpService already gave OtpController.
builder.Services.AddScoped<IUserProfileService, UserProfileService>();

// T019: Dependency Inversion (D in SOLID) - IdentityVerificationController
// asks for IIdentityVerificationService, never AppDbContext/IUserRepository/
// IFaceMatchService directly. Every identity-verification business rule
// (the photo_url precondition, the audit-row write, the badge flip) lives
// behind this one interface, the same shape IUserProfileService/IOtpService
// already gave their controllers.
builder.Services.AddScoped<IIdentityVerificationService, IdentityVerificationService>();

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

// T018: serves whatever ProfilePhotoStorageService writes under wwwroot
// (StoragePath "wwwroot/uploads/photos") back out over HTTP at the
// un-prefixed request path "/uploads/photos" - the no-argument overload maps
// a physical folder to a URL path of the same name automatically. This is
// DELIBERATELY un-prefixed even though ProfilePhotoOptions.BaseUrl (the
// PUBLIC photo_url returned to clients) is "/user/uploads/photos" - every
// route in this service is internally un-prefixed the same way (see
// AuthController's "/auth/google"), because api-gateway's YARP "user-route"
// strips the "/user" segment (PathRemovePrefix) before a request ever
// reaches this container. If StoragePath is ever reconfigured to a
// different physical folder, this call needs a custom
// StaticFileOptions/PhysicalFileProvider to keep pointing at it - only
// BaseUrl's "/user" prefix is exempt from needing to match, by design.
app.UseStaticFiles();

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
