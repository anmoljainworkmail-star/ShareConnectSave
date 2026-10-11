namespace chat_service.HostedServices;

using chat_service.Events;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;

// Pattern: IHostedService (background worker), implemented directly rather
// than via the BackgroundService base class the dotnet-mvc-controllers
// skill shows for T039's future OutboxRelayService. Both are valid .NET
// idioms for "run this regardless of HTTP traffic", built on the exact same
// host-lifecycle contract (AddHostedService<T>() accepts either, since
// BackgroundService itself just implements IHostedService's StartAsync/
// StopAsync with a while-loop-plus-Delay for you) - this class is written
// the lower-level way on purpose, as a second worked example in this
// learning project, using a System.Threading.Timer instead of an
// Execute-loop-with-Delay: StartAsync only ARMS a recurring timer and
// returns immediately (never blocks the host's startup), StopAsync disarms
// it, and the actual poll work happens on the timer's own callback thread.
public sealed class ChatAutoCloseHostedService : IHostedService, IDisposable
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<ChatAutoCloseHostedService> _logger;
    private readonly TimeSpan _pollInterval;
    private readonly TimeSpan _autoCloseThreshold;
    private Timer? _timer;

    public ChatAutoCloseHostedService(
        IServiceScopeFactory scopeFactory,
        IConfiguration configuration,
        ILogger<ChatAutoCloseHostedService> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;

        // No Hardcoded Config (CLAUDE.md rule 3 / this ticket's architecture
        // rules): same flat-key-with-fallback-default style as
        // ChatLifecycleService's grace period and Program.cs's
        // MONGO_TTL_SECONDS - every one of this ticket's three timing values
        // is overridable via docker-compose.override.yml, never a bare
        // magic number duplicated across files.
        var pollIntervalSeconds = int.TryParse(configuration["CHAT_AUTO_CLOSE_POLL_INTERVAL_SECONDS"], out var parsedPoll)
            ? parsedPoll
            : 300;
        _pollInterval = TimeSpan.FromSeconds(pollIntervalSeconds);

        var autoCloseThresholdHours = int.TryParse(configuration["CHAT_AUTO_CLOSE_THRESHOLD_HOURS"], out var parsedThreshold)
            ? parsedThreshold
            : 2;
        _autoCloseThreshold = TimeSpan.FromHours(autoCloseThresholdHours);
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        // dueTime == period == _pollInterval: the FIRST poll happens one
        // interval after startup, not immediately on boot - a freshly
        // started chat-service has no rooms old enough to auto-close yet
        // (T038 only just started accepting connection.accepted events), so
        // there is no value in racing the very first poll against startup.
        _timer = new Timer(FirePoll, state: null, dueTime: _pollInterval, period: _pollInterval);
        return Task.CompletedTask;
    }

    // Timer callbacks run on the ThreadPool and must not be async void in
    // anything caller-observable - this wrapper fires the real async work
    // and lets PollOnceAsync's own try/catch own every exception, so a
    // failing poll never becomes an unobserved exception on a ThreadPool
    // thread (which, depending on host configuration, can crash the whole
    // process).
    private void FirePoll(object? state) => _ = PollOnceAsync();

    private async Task PollOnceAsync()
    {
        try
        {
            // Dependency Gate / scoping: this class is registered as a
            // Singleton (AddHostedService always is), but IChatRoomRepository
            // and ICloseChatRoomService are Scoped (they ultimately wrap a
            // Scoped IMongoDatabase-backed repository) - resolving them
            // straight from a Singleton's constructor would either fail
            // outright or capture a never-disposed scope. Creating a fresh
            // scope per poll tick is the standard fix, same idiom
            // ChatLifecycleService.ScheduleGraceClose uses for its own
            // post-delay continuation.
            using var scope = _scopeFactory.CreateScope();
            var chatRoomRepository = scope.ServiceProvider.GetRequiredService<IChatRoomRepository>();
            var closeChatRoomService = scope.ServiceProvider.GetRequiredService<ICloseChatRoomService>();

            var cutoffUtc = DateTime.UtcNow - _autoCloseThreshold;
            var staleRooms = await chatRoomRepository.FindOpenRoomsOlderThanAsync(cutoffUtc);

            foreach (var room in staleRooms)
            {
                try
                {
                    await closeChatRoomService.CloseRoomAsync(room.Id!, ChatClosedReason.Timeout);
                }
                catch (Exception ex)
                {
                    // Per-room try/catch: one room's close failing (e.g. a
                    // transient Mongo hiccup) must not stop this poll from
                    // also closing every OTHER stale room it already found -
                    // the failed room simply gets picked up again on the
                    // next poll interval, since it is still OPEN and still
                    // past the threshold.
                    _logger.LogError(ex, "Auto-close failed for chat {ChatId}; will retry on next poll.", room.Id);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "ChatAutoCloseHostedService poll failed.");
        }
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _timer?.Change(Timeout.Infinite, 0);
        return Task.CompletedTask;
    }

    public void Dispose() => _timer?.Dispose();
}
