using Confluent.Kafka;
using notification_service.Messaging.Handlers;

namespace notification_service.Messaging.Consumers;

// Pattern: IHostedService (background worker, via BackgroundService) — same
// Kafka-mechanics shape as ConnectionRequestedConsumer/ConnectionAcceptedConsumer
// above; see ConnectionRequestedConsumer's comment for the full reasoning,
// not repeated three times in this file set.
public class ConnectionExpiredConsumer : BackgroundService
{
    private const string Topic = "connection.expired";
    private const string GroupId = "notification-service";

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IConfiguration _configuration;
    private readonly ILogger<ConnectionExpiredConsumer> _logger;

    public ConnectionExpiredConsumer(
        IServiceScopeFactory scopeFactory,
        IConfiguration configuration,
        ILogger<ConnectionExpiredConsumer> logger)
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
        var bootstrapServers = _configuration["KAFKA_BOOTSTRAP"]
            ?? throw new InvalidOperationException("KAFKA_BOOTSTRAP is not set");

        var config = new ConsumerConfig
        {
            BootstrapServers = bootstrapServers,
            GroupId = GroupId,
            AutoOffsetReset = AutoOffsetReset.Earliest,
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
                    using var scope = _scopeFactory.CreateScope();
                    var handler = scope.ServiceProvider.GetRequiredService<IConnectionExpiredHandler>();

                    handler.HandleAsync(result.Message.Value, stoppingToken).GetAwaiter().GetResult();

                    consumer.Commit(result);
                }
                catch (Exception ex)
                {
                    _logger.LogError(
                        ex,
                        "Failed to handle connection.expired message at offset {Offset}; will be redelivered.",
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
