namespace chat_service.HostedServices;

using Confluent.Kafka;
using chat_service.Entities;
using chat_service.Repositories.Interfaces;

// T039 - Kafka Producer: chat.closed (relay side).
//
// IMPORTANT deviation from this ticket's generic prose, left here so the
// next person who reads the ticket next to this file isn't confused: the
// ticket (written before T037 shipped) describes a SQL-Server-backed
// `outbox` table + EF Core migration + IOutboxService, mirroring
// connection-service's T032 Java implementation exactly. Chat Service has
// no SQL store at all - Mongo is its only database (see Program.cs, which
// never configures an AppDbContext) - and T037 already made an explicit
// Tech Lead architecture decision to fold the "pending outbox event" fields
// directly onto ChatRoomEntity instead (see that class's own comment for
// the full "why not a separate outbox collection" reasoning: this dev
// Mongo instance is a single standalone node, not a replica set, so it
// cannot do multi-document transactions, and a separate collection would
// need one to get the same atomicity a single-document update already
// gives for free). CloseChatRoomService already writes that "outbox row"
// (OutboxEventType/OutboxPayload/OutboxCreatedAt/OutboxPublishedAt=null)
// atomically, in the SAME write as Status=CLOSED and ClosedAt - the
// Outbox Pattern's "business data and event write commit together or not
// at all" guarantee that CLAUDE.md and the kafka-outbox skill call for is
// already satisfied by T037's code, not by anything in this file.
//
// This class is the piece T037 explicitly left for T039: the relay that
// polls for OutboxPublishedAt == null and actually talks to Kafka. It is
// the ONLY class in chat-service that is allowed to touch
// IProducer<string,string> - CloseChatRoomService (business code) has no
// idea Kafka exists, matching connection-service's OutboxRelay being the
// only class there allowed to inject KafkaTemplate (Dependency Inversion,
// SOLID D, and Single Responsibility, SOLID S: this class does not know
// what a chat room or a grace timer is, only how to move a pending event
// to Kafka).
//
// Pattern: IHostedService + System.Threading.Timer, same lower-level idiom
// as ChatAutoCloseHostedService (T037) rather than the BackgroundService
// base class ConnectionAcceptedConsumer (T038) uses - StartAsync only arms
// a recurring timer and returns immediately, StopAsync disarms it, and the
// poll work itself runs on the timer's callback.
public sealed class OutboxRelayHostedService : IHostedService, IDisposable
{
    // Batch size as a plain constant, not an env var - same choice
    // connection-service's OutboxRelay makes for its own BATCH_SIZE. This is
    // a tuning knob, not a secret/connection/business-rule value, so it does
    // not fall under CLAUDE.md's "no hardcoded config" rule the way a
    // connection string, port, or TTL would.
    private const int BatchSize = 50;

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IProducer<string, string> _producer;
    private readonly ILogger<OutboxRelayHostedService> _logger;
    private readonly TimeSpan _pollInterval;
    private Timer? _timer;

    public OutboxRelayHostedService(
        IServiceScopeFactory scopeFactory,
        IProducer<string, string> producer,
        IConfiguration configuration,
        ILogger<OutboxRelayHostedService> logger)
    {
        _scopeFactory = scopeFactory;
        _producer = producer;
        _logger = logger;

        // No Hardcoded Config (CLAUDE.md rule 3): same flat-key-with-
        // fallback-default style as ChatLifecycleService's grace period and
        // ChatAutoCloseHostedService's poll interval/threshold. 500ms matches
        // connection-service's OutboxRelay @Scheduled(fixedDelay = 500) -
        // kept configurable here (unlike the Java side's hardcoded literal)
        // because this is this specific service's own established
        // convention for every one of its timing values, not because this
        // ticket mandates diverging from the Java relay's cadence.
        var pollIntervalMs = int.TryParse(configuration["CHAT_OUTBOX_RELAY_POLL_INTERVAL_MS"], out var parsedPollMs)
            ? parsedPollMs
            : 500;
        _pollInterval = TimeSpan.FromMilliseconds(pollIntervalMs);
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        // dueTime == period == _pollInterval, same as ChatAutoCloseHostedService:
        // the first poll happens one interval after startup, not immediately
        // on boot - there cannot be a pending outbox row before this process
        // has even finished starting.
        _timer = new Timer(FirePoll, state: null, dueTime: _pollInterval, period: _pollInterval);
        return Task.CompletedTask;
    }

    // Timer callbacks run on the ThreadPool and must not be async void in
    // anything caller-observable - PollOnceAsync's own try/catch owns every
    // exception so a failing poll never becomes an unobserved exception on a
    // ThreadPool thread, same reasoning as ChatAutoCloseHostedService.FirePoll.
    private void FirePoll(object? state) => _ = PollOnceAsync();

    private async Task PollOnceAsync()
    {
        try
        {
            // Dependency Gate / scoping: this class is registered as a
            // Singleton (AddHostedService always is), but IChatRoomRepository
            // is Scoped - resolving it straight from a Singleton's
            // constructor would capture a never-disposed scope. Creating a
            // fresh scope per poll tick is the same idiom
            // ChatAutoCloseHostedService.PollOnceAsync already uses.
            using var scope = _scopeFactory.CreateScope();
            var chatRoomRepository = scope.ServiceProvider.GetRequiredService<IChatRoomRepository>();

            var pendingRooms = await chatRoomRepository.FindPendingOutboxRoomsAsync(BatchSize);

            foreach (var room in pendingRooms)
            {
                await PublishOneAsync(chatRoomRepository, room);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "OutboxRelayHostedService poll failed.");
        }
    }

    // Resilience, one row at a time - same discipline as
    // ChatAutoCloseHostedService's per-room try/catch and
    // connection-service's OutboxRelay.publishOne: one room's event failing
    // to publish (Kafka momentarily unreachable, broker rejects it, etc.)
    // must not stop this tick's batch from relaying every OTHER pending
    // event. Leaving a failed row's OutboxPublishedAt null is the entire
    // retry mechanism - no retry-count/DLQ bookkeeping here, per the ticket's
    // explicit "what NOT to do".
    private async Task PublishOneAsync(IChatRoomRepository chatRoomRepository, ChatRoomEntity room)
    {
        // Defensive guard clause: OutboxEventType/OutboxPayload are nullable
        // on the entity (a room that has never closed has neither), and Id
        // is nullable because MongoDB only assigns it on insert - but the
        // query that produced `room` already filtered OutboxEventType !=
        // null, and a document read back out of Mongo always has an Id. This
        // null-check exists only so the compiler's nullability analysis can
        // treat all three as non-null for the rest of this method (captured
        // into locals below), not because this branch is expected to ever run.
        if (room.Id is null || room.OutboxEventType is null || room.OutboxPayload is null)
        {
            return;
        }

        var chatId = room.Id;
        var topic = room.OutboxEventType;
        var payload = room.OutboxPayload;

        try
        {
            // ProduceAsync (not the fire-and-forget Produce() that
            // user-service's KafkaUserVerifiedEventPublisher uses) - that
            // producer is this project's ticket-approved EXCEPTION to the
            // outbox rule specifically because it has no outbox table behind
            // it yet (see its own class comment). This relay is the outbox
            // rule's normal case: awaiting ProduceAsync means the broker has
            // actually acknowledged the write before MarkOutboxPublishedAsync
            // runs below - the same guarantee Java's OutboxRelay gets by
            // calling kafkaTemplate.send(...).get() synchronously. Marking a
            // row published before Kafka has acknowledged it would silently
            // reopen the exact "DB says done, but nobody got the message"
            // gap the Outbox Pattern exists to close.
            //
            // room.Id (the chat_id) is the partition key - every event for
            // the same chat room lands on the same partition, matching the
            // ticket's explicit "Partition key: chat_id" spec and the
            // kafka-outbox skill's "never leave the key null" rule.
            await _producer.ProduceAsync(
                topic,
                new Message<string, string> { Key = chatId, Value = payload });

            await chatRoomRepository.MarkOutboxPublishedAsync(chatId, DateTime.UtcNow);
        }
        catch (Exception ex)
        {
            _logger.LogError(
                ex,
                "Failed to relay outbox event for chat {ChatId} (topic={Topic}) to Kafka - leaving unpublished for the next poll.",
                chatId,
                topic);
        }
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _timer?.Change(Timeout.Infinite, 0);
        return Task.CompletedTask;
    }

    public void Dispose() => _timer?.Dispose();
}
