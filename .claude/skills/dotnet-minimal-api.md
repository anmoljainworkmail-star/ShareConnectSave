# .NET 9 Minimal API Skill — ShareConnectSave Backend Services

You are implementing a .NET 9 Minimal API microservice for ShareConnectSave. This is a learning project — add short comments naming the pattern when it is non-obvious.

## Project structure

```
services/<service-name>/
  Program.cs                        ← composition root — all DI and pipeline config here
  Endpoints/
    <Feature>Endpoints.cs           ← static class with MapGroup extension method
  Domain/
    <Entity>.cs                     ← EF Core entity + C# record DTOs in same file
  Repositories/
    I<Feature>Repository.cs         ← interface (Dependency Inversion)
    <Feature>Repository.cs          ← EF Core implementation
  Services/
    I<Feature>Service.cs            ← interface
    <Feature>Service.cs
  Kafka/
    <Topic>Consumer.cs              ← IHostedService or BackgroundService
    <Topic>Producer.cs              ← uses IOutboxService, never IProducer directly
  Infrastructure/
    AppDbContext.cs                 ← DbContext with all DbSets
    Migrations/                     ← EF Core generated migrations
  Contracts/
    ErrorResponse.cs                ← shared record — same shape as Java GlobalExceptionHandler
  appsettings.json
  Dockerfile
```

## Minimal API endpoint pattern

```csharp
// Pattern: Endpoint grouping — feature slice owns its routes
// Reason: keeps Program.cs thin and each feature independently testable
public static class ProfileEndpoints
{
  public static RouteGroupBuilder MapProfileEndpoints(this RouteGroupBuilder group)
  {
    group.MapGet("/me", GetCurrentUser);
    group.MapPatch("/me", UpdateProfile);
    group.MapPost("/me/photo", UploadPhoto);
    return group;
  }

  private static async Task<IResult> GetCurrentUser(
    HttpContext ctx,
    IUserRepository repo)
  {
    // Identity from gateway-injected header — never re-validate JWT here
    var userId = Guid.Parse(ctx.Request.Headers["X-User-Id"]!);
    var user = await repo.FindByIdAsync(userId);
    return user is null ? Results.NotFound() : Results.Ok(user.ToResponse());
  }
}
```

Registration in Program.cs:
```csharp
app.MapGroup("/users").MapProfileEndpoints().RequireAuthorization();
```

## EF Core + dependency inversion

```csharp
// Pattern: Dependency Inversion (SOLID D)
// Reason: swapping SQLite in tests doesn't require changing service code
public interface IUserRepository
{
  Task<User?> FindByIdAsync(Guid id);
  Task<User?> FindByGoogleIdAsync(string googleId);
  Task AddAsync(User user);
}

public class UserRepository(AppDbContext db) : IUserRepository
{
  public Task<User?> FindByIdAsync(Guid id) =>
    db.Users.FirstOrDefaultAsync(u => u.Id == id);
}
```

## EF Core entity conventions

```csharp
public class User
{
  public Guid Id { get; init; } = Guid.NewGuid();
  public string GoogleId { get; set; } = string.Empty;
  public string Phone { get; set; } = string.Empty;
  public string Name { get; set; } = string.Empty;
  public string? PhotoUrl { get; set; }
  public Gender Gender { get; set; }
  public UserStatus Status { get; set; } = UserStatus.Unavailable;
  public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
}

public enum Gender { Unspecified, Male, Female }
public enum UserStatus { LookingForCompanion, Unavailable }
```

## ProblemDetails — error envelope

```csharp
// Pattern: consistent error envelope across all services
// Reason: Angular client and other services parse one shape regardless of which service responded
builder.Services.AddProblemDetails(opt =>
{
  opt.CustomizeProblemDetails = ctx =>
  {
    ctx.ProblemDetails.Extensions["code"] = ctx.ProblemDetails.Title?.ToUpper().Replace(' ', '_') ?? "INTERNAL_ERROR";
    ctx.ProblemDetails.Extensions["traceId"] = Activity.Current?.TraceId.ToString();
  };
});

// Usage: throw a typed exception, middleware maps it
throw new DomainException("NO_BASE_PHOTO", "Upload a profile photo before starting identity verification.");
```

Register exception handler in Program.cs:
```csharp
app.UseExceptionHandler();
app.UseStatusCodePages();
```

## SignalR hub

```csharp
// Pattern: Hub as thin coordinator — no business logic in hub methods
// Reason: hub methods are called by SignalR transport layer; mixing business logic here
//         makes it untestable and couples the transport to domain rules
public class ChatHub(IChatService chatService) : Hub
{
  public async Task JoinRoom(string chatId)
  {
    await Groups.AddToGroupAsync(Context.ConnectionId, chatId);
  }

  public async Task SendMessage(string chatId, string content)
  {
    var userId = Guid.Parse(Context.User!.FindFirst("X-User-Id")!.Value);
    var message = await chatService.SaveMessageAsync(chatId, userId, content);
    // Broadcast to the group — SignalR handles fan-out to all connections in the room
    await Clients.Group(chatId).SendAsync("ReceiveMessage", message);
  }
}
```

Redis backplane (shared between Chat and Notification services — both use DB 1):
```csharp
builder.Services.AddSignalR()
  .AddStackExchangeRedis(builder.Configuration["REDIS_CONNECTION"]!, opt =>
    opt.Configuration.DefaultDatabase = 1);  // DB 1 is SignalR backplane by convention
```

## BackgroundService — outbox relay

```csharp
// Pattern: Outbox Relay — Background Service polls outbox table every 500ms
// Reason: this separates the act of writing the event from publishing it to Kafka,
//         making both steps independently atomic (no split-brain between DB and Kafka)
public class OutboxRelayService(IServiceScopeFactory scopeFactory) : BackgroundService
{
  protected override async Task ExecuteAsync(CancellationToken ct)
  {
    while (!ct.IsCancellationRequested)
    {
      using var scope = scopeFactory.CreateScope();
      var relay = scope.ServiceProvider.GetRequiredService<IOutboxRelay>();
      await relay.PublishPendingAsync(ct);
      await Task.Delay(500, ct);
    }
  }
}
```

## Kafka producer — always via outbox

```csharp
// Never call IProducer<string, string>.ProduceAsync() directly from business code
// Always go through IOutboxService so the event is written in the same DB transaction
public class UserService(IUserRepository repo, IOutboxService outbox, AppDbContext db)
{
  public async Task VerifyUserAsync(Guid userId)
  {
    var user = await repo.FindByIdAsync(userId) ?? throw new NotFoundException();
    user.IsVerified = true;

    // Pattern: Outbox — both writes committed atomically; relay publishes to Kafka later
    outbox.Enqueue("user.verified", new UserVerifiedEvent(userId, user.Gender, DateTimeOffset.UtcNow));
    await db.SaveChangesAsync();  // commits both the user update and the outbox row
  }
}
```

## Kafka consumer — BackgroundService + idempotency

```csharp
public class ConnectionAcceptedConsumer(IServiceScopeFactory scopeFactory) : BackgroundService
{
  protected override async Task ExecuteAsync(CancellationToken ct)
  {
    // ... consumer setup ...
    while (!ct.IsCancellationRequested)
    {
      var result = consumer.Consume(ct);
      using var scope = scopeFactory.CreateScope();
      var handler = scope.ServiceProvider.GetRequiredService<IConnectionAcceptedHandler>();
      await handler.HandleAsync(result.Message.Value, ct);
    }
  }
}

public class ConnectionAcceptedHandler(IProcessedEventRepository processedEvents, IChatService chatService)
{
  public async Task HandleAsync(ConnectionAcceptedEvent evt, CancellationToken ct)
  {
    // Pattern: Idempotency — at-least-once delivery means this can arrive twice
    if (await processedEvents.ExistsAsync(evt.EventId)) return;

    await chatService.OpenRoomAsync(evt.ConnectionId, evt.RequesterId, evt.RecipientId);
    await processedEvents.RecordAsync(evt.EventId);
  }
}
```

## Configuration — environment variables only

```csharp
// appsettings.json has no secrets — only structure
// Actual values come from environment variables or Docker Compose env: section
builder.Configuration.AddEnvironmentVariables();

// Usage in code:
var connStr = builder.Configuration["DB_CONNECTION"];       // SQL Server connection string
var redisConn = builder.Configuration["REDIS_CONNECTION"];  // Redis connection string
var jwtSecret = builder.Configuration["JWT_SECRET"];        // Only used at gateway
```

## Identity headers (read, never validate)

```csharp
// Extension method — centralizes header extraction in one place
public static class HttpContextExtensions
{
  public static Guid GetUserId(this HttpContext ctx) =>
    Guid.Parse(ctx.Request.Headers["X-User-Id"]!);

  public static string GetUserRole(this HttpContext ctx) =>
    ctx.Request.Headers["X-User-Role"]!;

  public static string GetUserGender(this HttpContext ctx) =>
    ctx.Request.Headers["X-User-Gender"]!;
}
```

## OpenTelemetry tracing

```csharp
builder.Services.AddOpenTelemetry()
  .WithTracing(tracing => tracing
    .AddAspNetCoreInstrumentation()
    .AddHttpClientInstrumentation()
    .AddEntityFrameworkCoreInstrumentation()
    .AddOtlpExporter(opt => opt.Endpoint = new Uri(builder.Configuration["OTLP_ENDPOINT"]!)));
```

The W3C `traceparent` header is propagated automatically via `HttpClientInstrumentation` — no manual work needed.

## Testing conventions

```csharp
// xUnit + Moq — no real infrastructure in unit tests
public class UserServiceTests
{
  private readonly Mock<IUserRepository> _repo = new();
  private readonly Mock<IOutboxService> _outbox = new();

  [Fact]
  public async Task VerifyUser_ShouldEnqueueUserVerifiedEvent()
  {
    _repo.Setup(r => r.FindByIdAsync(It.IsAny<Guid>())).ReturnsAsync(new User());
    var sut = new UserService(_repo.Object, _outbox.Object, /* db mock */);
    await sut.VerifyUserAsync(Guid.NewGuid());
    _outbox.Verify(o => o.Enqueue("user.verified", It.IsAny<object>()), Times.Once);
  }
}
```
