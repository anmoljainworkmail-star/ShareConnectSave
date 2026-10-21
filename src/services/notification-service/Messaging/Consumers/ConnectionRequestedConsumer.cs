using Confluent.Kafka;
using notification_service.Messaging.Handlers;

namespace notification_service.Messaging.Consumers;

// Pattern: IHostedService (background worker, via BackgroundService) — this
// consumer mirrors chat-service's ConnectionAcceptedConsumer (T038) shape
// exactly: Task.Factory.StartNew(..., TaskCreationOptions.LongRunning, ...)
// because Confluent.Kafka's Consume() is a blocking native call;
// EnableAutoCommit = false + manual Commit() only after the handler returns
// without throwing; AutoOffsetReset.Earliest; a fresh
// IServiceScopeFactory.CreateScope() per message.
//
// Pattern: Single Responsibility (SOLID S) — this class's only job is Kafka
// mechanics. It has no idea what a connection.requested event means or what
// happens when one arrives — that is entirely IConnectionRequestedHandler's
// job (Dependency Inversion, SOLID D).
public class ConnectionRequestedConsumer : BackgroundService
{
    private const string Topic = "connection.requested";

    // Consumer group id is per-SERVICE, not per-listener-class — this is
    // notification-service's group across ALL THREE of this ticket's
    // consumers, same convention as every other .NET/Java consumer in this
    // codebase (see chat-service's ConnectionAcceptedConsumer comment).
    private const string GroupId = "notification-service";

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IConfiguration _configuration;
    private readonly ILogger<ConnectionRequestedConsumer> _logger;

    public ConnectionRequestedConsumer(
        IServiceScopeFactory scopeFactory,
        IConfiguration configuration,
        ILogger<ConnectionRequestedConsumer> logger)
    {
        _scopeFactory = scopeFactory;
        _configuration = configuration;
        _logger = logger;
    }

    protected override Task ExecuteAsync(CancellationToken stoppingToken)
    {
        return Task.Factory.StartNew(
            () => ConsumeLoop(stoppingToken),
            stoppingToken,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);
    }

    private void ConsumeLoop(CancellationToken stoppingToken)
    {
        // No Hardcoded Config (CLAUDE.md rule 3) — docker-compose.override.yml
        // already sets this for notification-service (same flat key every
        // other Kafka client in this project reads).
        var bootstrapServers = _configuration["KAFKA_BOOTSTRAP"]
            ?? throw new InvalidOperationException("KAFKA_BOOTSTRAP is not set");

        var config = new ConsumerConfig
        {
            BootstrapServers = bootstrapServers,
            GroupId = GroupId,

            // This is notification-service's first-ever subscription to
            // connection.requested — Earliest guarantees it does not
            // silently skip every request Connection Service published
            // before this consumer first came online.
            AutoOffsetReset = AutoOffsetReset.Earliest,

            // Consumer Idempotency (CLAUDE.md rule 2), paired with the manual
            // Commit() call below: the offset only advances AFTER
            // IConnectionRequestedHandler.HandleAsync returns without
            // throwing, and ConnectionRequestedHandler's processed_events
            // check (Redis-backed, see that repository's own comment) is
            // what makes a redelivery of the same message safe.
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
                    // Singleton-to-Scoped hand-off: this class is a Singleton
                    // (AddHostedService always is), but IConnectionRequestedHandler
                    // ultimately wraps Scoped repositories/services.
                    using var scope = _scopeFactory.CreateScope();
                    var handler = scope.ServiceProvider.GetRequiredService<IConnectionRequestedHandler>();

                    handler.HandleAsync(result.Message.Value, stoppingToken).GetAwaiter().GetResult();

                    consumer.Commit(result);
                }
                catch (Exception ex)
                {
                    // Deliberately NOT committing on failure: the offset
                    // stays where it was, so this exact message is
                    // redelivered on the next poll rather than silently
                    // skipped and lost.
                    _logger.LogError(
                        ex,
                        "Failed to handle connection.requested message at offset {Offset}; will be redelivered.",
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
