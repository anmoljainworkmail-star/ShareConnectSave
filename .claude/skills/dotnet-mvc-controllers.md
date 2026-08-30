# .NET 9 MVC Controllers Skill — ShareConnectSave Backend Services

You are implementing a .NET 9 Web API microservice for ShareConnectSave using MVC
Controllers (`[ApiController]`), not Minimal API endpoint-mapping. This project uses
Controllers specifically because its Java services use Spring Boot's `@RestController` —
attribute routing + constructor injection maps far more directly onto that model than
Minimal API's static-class-plus-`IEndpointRouteBuilder` style does, which matters since
this is a learning project and .NET + Spring Boot are being learned side by side. This is
a learning project generally — add short comments naming the pattern when it is
non-obvious.

## Project structure

```
services/<service-name>/
  Program.cs                        ← composition root — AddControllers()/MapControllers(), DI, pipeline config
  Controllers/
    <Feature>Controller.cs          ← [ApiController] class, one per feature slice, DTOs co-located
  Domain/
    <Entity>.cs                     ← EF Core entity + C# record DTOs in same file
  Repositories/
    Interfaces/
      I<Feature>Repository.cs       ← interface (Dependency Inversion) — kept separate so the
                                       repo's contract can be reviewed as one small folder
    <Feature>Repository.cs          ← EF Core implementation
  Services/
    Interfaces/
      I<Feature>Service.cs          ← same separation as Repositories/Interfaces
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

## MVC Controller pattern

```csharp
// Pattern: Controller as feature slice owner — same intent as Minimal API's
// endpoint grouping (keep Program.cs thin, each feature independently
// testable), just expressed as a class ASP.NET Core instantiates per
// request via DI instead of a static class + extension method.
// Spring Boot equivalent: @RestController class + @Autowired constructor
// injection — same shape, different annotations.
[ApiController]
public class ProfileController(IUserRepository repo) : ControllerBase
{
  [HttpGet("/users/me")]
  public async Task<IActionResult> GetCurrentUser()
  {
    // Identity from gateway-injected header — never re-validate JWT here
    var userId = long.Parse(HttpContext.Request.Headers["X-User-Id"]!);
    var user = await repo.FindByIdAsync(userId);
    return user is null ? NotFound() : Ok(user.ToResponse());
  }

  [HttpPatch("/users/me")]
  public async Task<IActionResult> UpdateProfile(UpdateProfileRequest request) { /* ... */ return Ok(); }
}
```

Registration in Program.cs:
```csharp
builder.Services.AddControllers();   // composition-root equivalent of Spring's component scan
// ...
app.MapControllers();
app.MapGroup("/users") is not used with Controllers — [HttpGet("/users/me")] on the
attribute IS the routing; there is no separate group-registration step to forget.
```

If a controller's every action should require auth, prefer `[Authorize]` on the class
over repeating it per action — same idea as Minimal API's `.RequireAuthorization()` on a
route group, just an attribute instead of a fluent call.

## EF Core + dependency inversion

Interface and implementation live in separate folders — `Repositories/Interfaces/` holds
only contracts, `Repositories/` holds only EF Core classes — so a reviewer can read every
repository's public surface in one folder without wading through query implementations.
Unchanged by the Controllers-vs-Minimal-API switch — this lives below the HTTP layer.

```csharp
// Repositories/Interfaces/IUserRepository.cs
namespace user_service.Repositories.Interfaces;

// Pattern: Dependency Inversion (SOLID D)
// Reason: swapping SQLite in tests doesn't require changing service code
public interface IUserRepository
{
  Task<User?> FindByIdAsync(long id);
  Task<User?> FindByGoogleIdAsync(string googleId);
  Task AddAsync(User user);
}
```

```csharp
// Repositories/UserRepository.cs
namespace user_service.Repositories;

using user_service.Repositories.Interfaces;

public class UserRepository(AppDbContext db) : IUserRepository
{
  public Task<User?> FindByIdAsync(long id) =>
    db.Users.FirstOrDefaultAsync(u => u.Id == id);
}
```

## EF Core entity conventions

```csharp
public class User
{
  public long Id { get; set; }
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

// Usage inside a controller action: throw a typed exception, middleware maps it
throw new DomainException("NO_BASE_PHOTO", "Upload a profile photo before starting identity verification.");
```

Register exception handler in Program.cs:
```csharp
app.UseExceptionHandler();
app.UseStatusCodePages();
```

For a single explicit error response inside an action (not an unexpected exception),
return a shaped result directly rather than throwing — see `AuthController.InvalidGoogleToken()`
for the pattern: `StatusCode(401, new ErrorResponse("CODE", "message", HttpContext.TraceIdentifier))`.

## SignalR hub

SignalR hubs are unaffected by the Controllers-vs-Minimal-API choice — a `Hub` subclass
is its own ASP.NET Core primitive, mapped via `app.MapHub<T>("/path")` regardless of how
your HTTP controllers are structured.

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
    var userId = long.Parse(Context.User!.FindFirst("X-User-Id")!.Value);
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
  public async Task VerifyUserAsync(long userId)
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
// Extension method — centralizes header extraction in one place, usable from
// any controller action via `HttpContext.GetUserId()` etc.
public static class HttpContextExtensions
{
  public static long GetUserId(this HttpContext ctx) =>
    long.Parse(ctx.Request.Headers["X-User-Id"]!);

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
// xUnit + Moq — no real infrastructure in unit tests. Controllers instantiate
// like any other class under test — construct one directly with mocked
// dependencies, no ASP.NET Core test host required for a pure unit test.
public class AuthControllerTests
{
  private readonly Mock<IGoogleTokenValidator> _validator = new();
  private readonly Mock<IUserRepository> _repo = new();
  private readonly Mock<IJwtIssuer> _jwtIssuer = new();

  [Fact]
  public async Task GoogleSignIn_InvalidToken_Returns401WithErrorCode()
  {
    _validator.Setup(v => v.ValidateAsync(It.IsAny<string>(), It.IsAny<CancellationToken>()))
      .ReturnsAsync((GoogleTokenPayload?)null);
    var sut = new AuthController(_validator.Object, _repo.Object, _jwtIssuer.Object)
    {
      ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
    };

    var result = await sut.GoogleSignIn(new GoogleAuthRequest("bad-token"));

    var objectResult = Assert.IsType<ObjectResult>(result);
    Assert.Equal(401, objectResult.StatusCode);
  }
}
```

For an integration test that exercises real attribute routing/model binding, use
`WebApplicationFactory<Program>` — this is where Controllers and Minimal API tests
actually differ syntactically, since Minimal API integration tests hit the same
`WebApplicationFactory` but there's no `[ApiController]` model-binding-inference
behavior to account for.
