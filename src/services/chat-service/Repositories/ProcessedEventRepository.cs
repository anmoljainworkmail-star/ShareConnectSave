using chat_service.Entities;
using chat_service.Repositories.Interfaces;
using MongoDB.Driver;

namespace chat_service.Repositories;

// Concrete Mongo implementation of IProcessedEventRepository - see that
// interface for the Repository/Interface Segregation/Dependency Inversion
// reasoning. This class's only job is translating the two idempotency-ledger
// operations into IMongoCollection<ProcessedEventEntity> calls; it holds no
// business rules (it does not decide WHAT to do with a not-yet-processed
// event - that is ConnectionAcceptedHandler's job).
public sealed class ProcessedEventRepository : IProcessedEventRepository
{
    private readonly IMongoCollection<ProcessedEventEntity> _processedEvents;

    public ProcessedEventRepository(IMongoDatabase database)
    {
        _processedEvents = database.GetCollection<ProcessedEventEntity>("processed_events");
    }

    public async Task<bool> ExistsAsync(string eventId)
    {
        // CountDocumentsAsync with a Limit of 1 - this only needs a yes/no
        // answer, not the document itself or an exact count, so there is no
        // reason to fetch anything beyond the first match.
        var count = await _processedEvents.CountDocumentsAsync(
            e => e.EventId == eventId,
            new CountOptions { Limit = 1 });

        return count > 0;
    }

    public async Task RecordAsync(string eventId)
    {
        var entity = new ProcessedEventEntity
        {
            EventId = eventId,
            ProcessedAt = DateTime.UtcNow,
        };

        try
        {
            await _processedEvents.InsertOneAsync(entity);
        }
        catch (MongoWriteException ex) when (ex.WriteError.Category == ServerErrorCategory.DuplicateKey)
        {
            // Database-level idempotency backstop (see ProcessedEventEntity's
            // own comment): EventId as this document's _id means Mongo's
            // built-in unique index is what actually guarantees "this event
            // id is recorded at most once", not just the ExistsAsync check a
            // caller ran a moment earlier. Two near-simultaneous consumer
            // instances racing to record the SAME event_id both observed
            // ExistsAsync == false is the one scenario this guards against -
            // the loser's insert fails here instead of silently overwriting
            // (InsertOneAsync, unlike an upsert, would otherwise throw and
            // propagate), and swallowing it is correct because the row this
            // caller wanted to exist already does.
        }
    }
}
