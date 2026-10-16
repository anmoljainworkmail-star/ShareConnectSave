using Confluent.Kafka;
using chat_service.Messaging.Handlers;

namespace chat_service.Messaging.Consumers;

// Pattern: IHostedService (background worker, via BackgroundService) - this
// is chat-service's FIRST Kafka consumer (T038); every prior background
// worker in this service (ChatAutoCloseHostedService, T037) is a timer-based
// poller, not a Kafka subscriber. This class is written the
// BackgroundService-base-class way, matching the dotnet-mvc-controllers
// skill's own worked example, as a second idiom alongside
// ChatAutoCloseHostedService's lower-level IHostedService + Timer - both are
// valid .NET ways to "run regardless of HTTP traffic".
//
// Pattern: Single Responsibility (SOLID S) - this class's only job is Kafka
// mechanics (subscribe, poll, commit). It has NO idea what a
// connection.accepted event means or what happens when one arrives - that
// is entirely IConnectionAcceptedHandler's job (Dependency Inversion,
// SOLID D: this class depends on the interface, resolved fresh per message
// from a DI scope, never on the concrete handler).
public class ConnectionAcceptedConsumer : BackgroundService
{
    private const string Topic = "connection.accepted";

    // groupId "chat-service": one consumer group per SERVICE, not per
    // listener class - the same platform convention discovery-service's and
    // connection-service's Java @KafkaListener consumers already use (see
    // their own groupId comments, e.g. TrustScoreUpdatedConsumer). This is
    // chat-service's first .NET consumer, but that changes nothing about the
    // convention: a future second .NET consumer in this same service would
    // share this identical group id.
    private const string GroupId = "chat-service";

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IConfiguration _configuration;
    private readonly ILogger<ConnectionAcceptedConsumer> _logger;

    public ConnectionAcceptedConsumer(
        IServiceScopeFactory scopeFactory,
        IConfiguration configuration,
        ILogger<ConnectionAcceptedConsumer> logger)
    {
        _scopeFactory = scopeFactory;
        _configuration = configuration;
        _logger = logger;
    }

    protected override Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // TaskCreationOptions.LongRunning: Confluent.Kafka's Consume() is a
        // blocking, synchronous native call (it wraps librdkafka) - it is
        // NOT a real `async` operation despite living inside an async
        // method's loop in the dotnet-mvc-controllers skill's simplified
        // example. Awaiting ExecuteAsync's loop directly on the default
        // ThreadPool would tie up a shared worker thread for this service's
        // entire lifetime; requesting a dedicated, explicitly long-running
        // thread here is what keeps this poll loop from starving other
        // ThreadPool work in the same process (e.g. ASP.NET Core request
        // handling, or ChatAutoCloseHostedService's own timer callbacks).
        return Task.Factory.StartNew(
            () => ConsumeLoop(stoppingToken),
            stoppingToken,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);
    }

    private void ConsumeLoop(CancellationToken stoppingToken)
    {
        // No Hardcoded Config (CLAUDE.md rule 3): the exact same
        // KAFKA_BOOTSTRAP flat key user-service's KafkaUserVerifiedEventPublisher
        // producer already reads via Program.cs - one broker address, shared
        // by every producer/consumer in this project, never a literal here.
        // docker-compose.override.yml already sets this for chat-service
        // (see that file's chat-service block).
        var bootstrapServers = _configuration["KAFKA_BOOTSTRAP"]
            ?? throw new InvalidOperationException("KAFKA_BOOTSTRAP is not set");

        var config = new ConsumerConfig
        {
            BootstrapServers = bootstrapServers,
            GroupId = GroupId,

            // Mirrors every Java consumer's auto-offset-reset: earliest (see
            // discovery-service/application.yml) - this is chat-service's
            // first-ever subscription to connection.accepted, so there is no
            // prior committed offset for this group. Starting from earliest,
            // rather than the Kafka client's own default of latest, is what
            // guarantees this consumer group does not silently skip every
            // event Connection Service published before chat-service first
            // came online.
            AutoOffsetReset = AutoOffsetReset.Earliest,

            // Consumer Idempotency (CLAUDE.md rule 2), paired with the manual
            // Commit() call below: with auto-commit OFF, an offset only
            // advances AFTER IConnectionAcceptedHandler.HandleAsync returns
            // without throwing. If it throws, the SAME message is redelivered
            // on the next poll - and ConnectionAcceptedHandler's
            // processed_events check is what makes that redelivery safe,
            // rather than a duplicate chat room. Same auto-commit-off +
            // manual-commit shape as discovery-service's application.yml
            // (enable-auto-commit: false).
            EnableAutoCommit = false,
        };

        using var consumer = new ConsumerBuilder<string, string>(config).Build();
        consumer.Subscribe(Topic);

        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                ConsumeResult<string, string>? result;
                try
                {
                    // Blocking call, passed the stopping token so a graceful
                    // shutdown (StopAsync) actually unblocks this loop
                    // instead of waiting indefinitely for the next message,
                    // which might never arrive before the host wants to exit.
                    result = consumer.Consume(stoppingToken);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (ConsumeException ex)
                {
                    _logger.LogError(ex, "Error consuming from {Topic}; continuing to poll.", Topic);
                    continue;
                }

                if (result?.Message?.Value is null)
                {
                    continue;
                }

                try
                {
                    // Dependency Gate / scoping: this class is registered as
                    // a Singleton (AddHostedService always is), but
                    // IConnectionAcceptedHandler ultimately wraps Scoped
                    // repositories/services - the exact same "fresh scope
                    // per unit of work" idiom ChatAutoCloseHostedService's
                    // PollOnceAsync and ChatLifecycleService.ScheduleGraceClose
                    // already use for their own Singleton-to-Scoped hand-off.
                    using var scope = _scopeFactory.CreateScope();
                    var handler = scope.ServiceProvider.GetRequiredService<IConnectionAcceptedHandler>();

                    handler.HandleAsync(result.Message.Value, stoppingToken).GetAwaiter().GetResult();

                    // Manual commit, only reached if HandleAsync returned
                    // without throwing - see EnableAutoCommit's comment
                    // above for the full guarantee this ordering provides.
                    consumer.Commit(result);
                }
                catch (Exception ex)
                {
                    // Deliberately NOT committing on failure: the offset
                    // stays where it was, so this exact message is
                    // redelivered on the next poll (or after this consumer
                    // restarts) rather than silently skipped and lost.
                    _logger.LogError(
                        ex,
                        "Failed to handle connection.accepted message at offset {Offset}; will be redelivered.",
                        result.Offset);
                }
            }
        }
        finally
        {
            consumer.Close();
        }
    }
}
