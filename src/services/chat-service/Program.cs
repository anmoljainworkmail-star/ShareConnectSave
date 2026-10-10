using chat_service.Hubs;
using chat_service.Infrastructure;
using chat_service.Repositories;
using chat_service.Repositories.Interfaces;
using chat_service.Services;
using chat_service.Services.Interfaces;
using MongoDB.Driver;

var builder = WebApplication.CreateBuilder(args);

// MVC Controllers (same reasoning as user-service's Program.cs, see that
// file's comment): attribute-routed controllers, matching every other
// service's routing style since several of them are Spring Boot
// @RestController-based. T035 registered this pipeline before any
// controller existed (see that ticket's own comment) specifically so this
// ticket - the one that actually adds ChatController - could land without
// touching Program.cs's shape again.
builder.Services.AddControllers();

// Global Exception Handling + Error Envelope (CLAUDE.md rule 6) - copied
// from user-service's identical registration (see that file's comment for
// the full reasoning). This is chat-service's first ticket with an actual
// HTTP surface to protect; T035 had no controllers, so there was nothing
// for an unhandled exception to happen in yet.
builder.Services.AddExceptionHandler<GlobalExceptionHandler>();
builder.Services.AddProblemDetails();

// No Hardcoded Config: the Mongo connection string is never a literal here.
// docker-compose.override.yml sets ConnectionStrings__ChatDb (double
// underscore = ASP.NET Core's nested-config separator), which binds to
// configuration key "ConnectionStrings:ChatDb" - same GetConnectionString
// pattern user-service's Program.cs already uses for "UserDb"/SQL Server,
// just a Mongo connection string instead of a SqlServer one.
var chatDbConnectionString = builder.Configuration.GetConnectionString("ChatDb")
    ?? throw new InvalidOperationException("ConnectionStrings__ChatDb is not set");

// The database name is parsed OUT of the connection string (via MongoUrl)
// rather than hardcoded as a second "ChatServiceDb" literal somewhere in
// this file. One source of truth: whatever database the connection string
// points at is the database this service opens - there's no way for this
// service and its own connection string to name two different databases by
// accident.
var chatDbName = MongoUrl.Create(chatDbConnectionString).DatabaseName
    ?? throw new InvalidOperationException("ConnectionStrings__ChatDb does not specify a database name");

// Singleton (via DI container, classic pattern from CLAUDE.md's pattern
// table): MongoClient internally owns a connection pool and is explicitly
// documented by the driver as safe - and intended - to be shared for the
// lifetime of the process, the same "build once, reuse everywhere" reasoning
// user-service's Program.cs applies to its Kafka IProducer<>. A new
// MongoClient per request would defeat that pooling entirely.
builder.Services.AddSingleton<IMongoClient>(_ => new MongoClient(chatDbConnectionString));

// IMongoDatabase is cheap to obtain from an IMongoClient (no new connections
// opened), but it's still resolved once here via DI rather than re-derived
// in every repository constructor, so chatDbName's parsing above stays the
// single place that decides which database every collection in this service
// talks to.
builder.Services.AddSingleton(sp => sp.GetRequiredService<IMongoClient>().GetDatabase(chatDbName));

// Dependency Inversion (SOLID D) + Interface Segregation (SOLID I): a future
// controller/SignalR hub asks the DI container for IChatRoomRepository /
// IMessageRepository, never for the concrete Mongo classes - this is the one
// place that binds each narrow abstraction to today's Mongo implementation.
builder.Services.AddScoped<IChatRoomRepository, ChatRoomRepository>();
builder.Services.AddScoped<IMessageRepository, MessageRepository>();

// Registered as a plain service (not a static helper) so it can be
// constructor-injected and swapped/mocked like anything else in this
// project's DI-first style - see its own class comment for why TTL index
// setup is split out from the repositories.
builder.Services.AddSingleton<MongoIndexInitializer>();

// Dependency Inversion (SOLID D): ChatHub and ChatController both ask the DI
// container for IChatService, never for IChatRoomRepository/IMessageRepository
// directly - see that interface's own comment. Scoped to match the
// repositories it wraps (both already AddScoped above).
builder.Services.AddScoped<IChatService, ChatService>();

// No Hardcoded Config: same category as KAFKA_BOOTSTRAP's flat-key read in
// user-service's Program.cs - one cross-cutting value, not a grouped
// IOptions<T> section. docker-compose.override.yml already sets this for
// chat-service as SIGNALR_REDIS_CONNECTION (deliberately NOT named
// REDIS_CONNECTION - this service's Redis traffic is exclusively the
// SignalR backplane, so the env var name says so rather than implying a
// general-purpose Redis connection other code here might reach for).
var signalRRedisConnection = builder.Configuration["SIGNALR_REDIS_CONNECTION"]
    ?? throw new InvalidOperationException("SIGNALR_REDIS_CONNECTION is not set");

// Cache-Aside boundary / Database-per-Service, applied to Redis DATABASE
// INDEXES rather than separate Redis instances (system design, per ticket):
// Discovery Service (T008) already uses Redis DB 0 for its profile cache.
// Both services share the SAME Redis container in docker-compose.yml, so
// without an explicit DefaultDatabase here, SignalR's backplane Pub/Sub
// traffic would land in DB 0 too and could collide with Discovery's cached
// keys - two unrelated concerns silently sharing one keyspace. DB 1 is
// reserved, by convention (see the dotnet-mvc-controllers skill file), for
// every service's SignalR backplane - Notification Service also sets
// SIGNALR_REDIS_CONNECTION in docker-compose.override.yml for its own future
// hub, even though its Program.cs is still a scaffold stub as of this
// ticket and doesn't call AddStackExchangeRedis yet. Sharing DB 1 is safe
// across services specifically because SignalR's backplane uses Redis
// Pub/Sub channels, not keys, so two different services' hubs publishing
// into the same DB 1 don't collide with each other the way two services
// writing cache KEYS into the same DB would.
//
// Observer (GoF, via SignalR groups + this backplane): without this
// package/configuration, Clients.Group(...).SendAsync(...) in ChatHub would
// only reach connections held by the SAME process - a second chat-service
// replica's connections would never see the broadcast. The Redis backplane
// is what lets every replica subscribe to the same Pub/Sub channel and
// relay each other's group broadcasts, regardless of which replica a given
// client's WebSocket happens to be connected to.
builder.Services.AddSignalR()
    .AddStackExchangeRedis(signalRRedisConnection, options =>
        options.Configuration.DefaultDatabase = 1);

var app = builder.Build();

// No Hardcoded Config: TTL duration is a business rule (the actual privacy
// guarantee duration), not a constant baked into code. Read as a flat key
// (same category as user-service's KAFKA_BOOTSTRAP/TWILIO_STUB reads - a
// single cross-cutting value, not a grouped IOptions<T> section) because
// T007's mongo-init script is this value's source of truth; this service
// only consumes it, it never redefines or re-defaults it independently of
// that script's own 7200s default.
var ttlSeconds = int.TryParse(builder.Configuration["MONGO_TTL_SECONDS"], out var parsedTtl)
    ? parsedTtl
    : 7200;

// Dependency Gate (same intent as user-service's DB-backed startup checks):
// this service must not accept traffic before it has confirmed both (a) it
// can actually reach MongoDB, and (b) the TTL index that makes chat
// ephemeral is in place. Awaiting this BEFORE app.Run() is what makes
// "service starts connected to MongoDB" a guarantee enforced at startup,
// not an assumption the first request happens to validate.
using (var scope = app.Services.CreateScope())
{
    var mongoDatabase = scope.ServiceProvider.GetRequiredService<IMongoDatabase>();
    var indexInitializer = scope.ServiceProvider.GetRequiredService<MongoIndexInitializer>();
    await indexInitializer.EnsureMessagesTtlIndexAsync(mongoDatabase, ttlSeconds);
}

// Registered as early as possible so it wraps every downstream middleware/
// controller action - see the AddExceptionHandler registration above for
// why this exists (chat-service's first ticket with real endpoints to
// protect).
app.UseExceptionHandler();
app.UseStatusCodePages();

app.MapControllers();

// SignalR hub endpoint (T036's real-time delivery layer). "/hubs/chat"
// matches the dotnet-mvc-controllers skill's documented convention
// (app.MapHub<T>("/path")) - hub mapping is its own ASP.NET Core primitive,
// unaffected by this service's controllers-vs-minimal-API routing choice.
app.MapHub<ChatHub>("/hubs/chat");

// Health Endpoint as a Dependency Gate (same shape as user-service's
// "/health", see that file's comment): liveness only. Docker Compose's
// healthcheck for chat-service polls this route, and every service that
// depends_on chat-service with condition: service_healthy relies on it being
// a real "the process is up" signal. Readiness (e.g. a live Mongo ping on
// every poll) is deliberately out of scope - the startup block above already
// guarantees Mongo was reachable before this endpoint ever started serving.
app.MapGet("/health", () => Results.Ok());

app.Run();
