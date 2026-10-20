using FirebaseAdmin;
using Google.Apis.Auth.OAuth2;
using notification_service.Clients;
using notification_service.Hubs;
using notification_service.Services;

var builder = WebApplication.CreateBuilder(args);

// MVC Controllers composition root (dotnet-mvc-controllers skill): this call
// is what turns [ApiController] attribute-routed classes into something the
// pipeline will actually dispatch to - the Controllers equivalent of
// Minimal API's per-route app.MapGet/app.MapPost calls, done once here
// instead of once per route.
builder.Services.AddControllers();

// process.env-for-config (CLAUDE.md "Across All Projects" preference, .NET
// terms): IConfiguration already reads environment variables by default in
// WebApplication.CreateBuilder, but this call is kept explicit so the
// "never hardcode, always configuration-bound" rule is visible at the call
// site, not just an implicit framework default a future reader has to know.
builder.Configuration.AddEnvironmentVariables();

// Fail fast, not on first SendAsync call: a missing credentials path is a
// deployment/config error, not a runtime condition any caller should have to
// discover mid-request. Guard clause (CLAUDE.md rule) over letting
// FirebaseApp.Create throw its own less-obvious exception deeper in.
var fcmCredentialsPath = builder.Configuration["FCM_CREDENTIALS_JSON"];
if (string.IsNullOrWhiteSpace(fcmCredentialsPath))
{
    throw new InvalidOperationException(
        "FCM_CREDENTIALS_JSON is not configured. Set it to the file path of the Firebase service account JSON credentials - never hardcode the path or embed the JSON contents.");
}

// Pattern: Singleton (via DI container). A FirebaseApp holds live
// credentials and the SDK's internal HTTP/token-refresh machinery - the
// Firebase Admin SDK docs explicitly call it expensive to construct and safe
// to share for the life of the process. Building it exactly once here, in
// the composition root, and registering that single instance is the same
// guarantee as CLAUDE.md's shared-Redis-multiplexer example: one instance
// reused by every request, never rebuilt per request. FirebaseApp.Create
// also stores itself as FirebaseApp.DefaultInstance, which is what
// FcmNotificationService's FirebaseMessaging.DefaultInstance call resolves
// against - so registering it here is what makes that later call safe.
var firebaseApp = FirebaseApp.Create(new AppOptions
{
    Credential = GoogleCredential.FromFile(fcmCredentialsPath),
});
builder.Services.AddSingleton(firebaseApp);

// Dependency Inversion (SOLID D) + a small HTTP client abstraction: Fcm
// NotificationService never talks to HttpClient directly, only to
// IUserServiceClient. AddHttpClient<TInterface, TImplementation> both
// registers UserServiceClient as IUserServiceClient's implementation AND
// hands it a pooled, DI-managed HttpClient (correct handler lifetime, no
// socket exhaustion under load) - the .NET equivalent of the WebClient this
// project's Java services inject for the same kind of outbound call.
var userServiceBaseUrl = builder.Configuration["USER_SERVICE_BASE_URL"] ?? "http://user-service";
builder.Services.AddHttpClient<IUserServiceClient, UserServiceClient>(client =>
{
    client.BaseAddress = new Uri(userServiceBaseUrl);
});

builder.Services.AddScoped<IFcmNotificationService, FcmNotificationService>();

// T048: No Hardcoded Config - same flat-key category as FCM_CREDENTIALS_JSON
// above and chat-service's identical read (see that Program.cs's comment
// for the full "why DB 1" reasoning). docker-compose.override.yml already
// sets this for notification-service as SIGNALR_REDIS_CONNECTION - the
// exact same env var name chat-service reads - so both services' hubs
// resolve to the same Redis config entry without either one needing to
// know the other service exists.
var signalRRedisConnection = builder.Configuration["SIGNALR_REDIS_CONNECTION"]
    ?? throw new InvalidOperationException("SIGNALR_REDIS_CONNECTION is not set");

// Observer (GoF, via SignalR groups + this backplane) - identical reasoning
// to chat-service's AddStackExchangeRedis call: without this, Clients.Group
// pushes from a future Kafka consumer (resolved via IHubContext<NotificationHub>)
// would only reach connections held by the SAME process. DB 1 is the
// convention this project reserves for every service's SignalR backplane
// (Discovery Service already owns DB 0 on this same Redis container for its
// profile cache) - chat-service and notification-service sharing DB 1 is
// safe specifically because SignalR's backplane multiplexes over Pub/Sub
// channels, not keys, so the two hubs' broadcasts never collide the way two
// services writing cache KEYS into the same DB would.
builder.Services.AddSignalR()
    .AddStackExchangeRedis(signalRRedisConnection, options =>
        options.Configuration.DefaultDatabase = 1);

var app = builder.Build();

app.MapControllers();

// SignalR hub endpoint (T048's real-time delivery layer - Notification
// Service's foreground-channel counterpart to T047's FCM background
// channel). "/hubs/notifications" mirrors chat-service's "/hubs/chat"
// mapping convention exactly (dotnet-mvc-controllers skill: hub mapping is
// its own ASP.NET Core primitive, unaffected by this service's
// controllers-vs-minimal-API routing choice).
app.MapHub<NotificationHub>("/hubs/notifications");

// Left over from the T001 scaffold - harmless liveness placeholder, not a
// business endpoint, so it is not worth moving into a controller for this
// ticket's scope.
app.MapGet("/", () => "Hello World!");

app.Run();
