using notification_service.Repositories.Interfaces;
using StackExchange.Redis;

namespace notification_service.Repositories;

// Concrete Redis implementation of IProcessedEventRepository — see that
// interface for the Repository/Interface Segregation/Dependency Inversion/
// Liskov Substitution reasoning. This class's only job is translating the
// two idempotency-ledger operations into IDatabase calls against Redis
// logical DB 2; it holds no business rules (it does not decide WHAT to do
// with a not-yet-processed event — that is each Connection*Handler's job,
// exactly like chat-service's Mongo version).
//
// Idempotency (kafka-outbox skill) + Redis TTL, in place of a cron prune job:
// the SQL/Mongo shape of this pattern needs a scheduled job to delete rows
// older than Kafka's own 7-day retention window, because that storage never
// forgets anything on its own. Redis's native per-key TTL (RecordAsync below)
// IS that cleanup — the key expires itself the moment it stops being able to
// matter (Kafka could never redeliver a message older than its own retention
// window), so there is nothing here for a future maintainer to remember to
// run.
//
// DB index 2, never DB 1: this service's SignalR backplane (T048) already
// owns Redis DB 1 (see Program.cs's AddStackExchangeRedis call) — reusing DB
// 1 for idempotency keys would mix two unrelated keyspaces on the same
// logical database, exactly the collision src/infra/README.md's DB-index
// table exists to prevent. DB 2 is the next free index in that same
// convention (see this ticket's own update to that file).
public sealed class ProcessedEventRepository : IProcessedEventRepository
{
    private const int IdempotencyDbIndex = 2;
    private static readonly TimeSpan KafkaRetentionWindow = TimeSpan.FromDays(7);

    private readonly IDatabase _database;

    public ProcessedEventRepository(IConnectionMultiplexer redis)
    {
        _database = redis.GetDatabase(IdempotencyDbIndex);
    }

    public Task<bool> ExistsAsync(string eventId) =>
        _database.KeyExistsAsync(Key(eventId));

    public Task RecordAsync(string eventId) =>
        // TTL matches Kafka's own 7-day message retention (see class comment
        // above) — this value, not an arbitrary "long enough" guess, is what
        // makes the TTL a correctness mechanism rather than a magic number: a
        // redelivery can never arrive after Kafka itself has discarded the
        // message, so the key never needs to outlive that window.
        _database.StringSetAsync(Key(eventId), "1", KafkaRetentionWindow);

    private static string Key(string eventId) => $"processed_events:{eventId}";
}
