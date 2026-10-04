using chat_service.Entities;
using MongoDB.Bson;
using MongoDB.Driver;

namespace chat_service.Infrastructure;

// Pattern: Single Responsibility (SOLID S) - this class's only job is
// ensuring the `messages` collection's TTL index exists and is correct.
// Repositories (ChatRoomRepository/MessageRepository) do CRUD only; schema
// and index setup is deliberately kept separate so neither concern has to
// know about the other.
//
// Pattern: Idempotency - the project applies "processing the same thing
// twice produces the same result as once" to Kafka consumers elsewhere
// (processed_events table); here the same guarantee is applied to a startup
// migration instead of message processing. EnsureMessagesTtlIndexAsync is
// safe to call every single time this service starts, including against a
// Mongo instance that already has the correct index.
//
// Pattern: Defense-in-depth (system design) - T007's mongo-init script
// (src/infra/mongo-init/01-init-chat.js) already creates this same TTL index
// when the mongodb container first starts. This class creates/confirms it
// again, independently, every time the SERVICE starts. If a developer ever
// points chat-service at a fresh Mongo instance that never ran the init
// script, the ephemeral-chat privacy guarantee still holds because this
// class put the index there itself - the guarantee does not depend on a
// human remembering to run a separate script.
public sealed class MongoIndexInitializer
{
    private const string MessagesCollectionName = "messages";
    private const string TtlField = "sent_at";

    public async Task EnsureMessagesTtlIndexAsync(IMongoDatabase database, int ttlSeconds, CancellationToken cancellationToken = default)
    {
        var messages = database.GetCollection<MessageEntity>(MessagesCollectionName);

        using var cursor = await messages.Indexes.ListAsync(cancellationToken);
        var existingIndexes = await cursor.ToListAsync(cancellationToken);

        // Mirrors T007's ensureTtlIndex JS guard: find an index keyed on the
        // TTL field that already carries an expireAfterSeconds value (as
        // opposed to a plain non-TTL index on the same field, which
        // theoretically could exist but doesn't here).
        var existing = existingIndexes.FirstOrDefault(ix =>
            ix.TryGetValue("key", out var key)
            && key.AsBsonDocument.Contains(TtlField)
            && ix.Contains("expireAfterSeconds"));

        if (existing is not null)
        {
            var currentTtl = existing["expireAfterSeconds"].ToInt64();
            if (currentTtl == ttlSeconds)
            {
                // Already correct - safe no-op, matches the JS script's
                // "already correct, nothing to do" early return.
                return;
            }

            // Idempotency wrinkle: a bare CreateOneAsync() with a DIFFERENT
            // expireAfterSeconds than what's already indexed throws
            // MongoCommandException (IndexOptionsConflict) rather than
            // silently updating it - Mongo refuses to redefine an existing
            // index's options via createIndex. Dropping first, exactly like
            // the init script does, is what makes this method idempotent
            // under a changed MONGO_TTL_SECONDS, not just under an unchanged
            // one.
            var existingName = existing["name"].AsString;
            await messages.Indexes.DropOneAsync(existingName, cancellationToken);
        }

        var keys = Builders<MessageEntity>.IndexKeys.Ascending(m => m.SentAt);
        var options = new CreateIndexOptions { ExpireAfter = TimeSpan.FromSeconds(ttlSeconds) };
        await messages.Indexes.CreateOneAsync(new CreateIndexModel<MessageEntity>(keys, options), cancellationToken: cancellationToken);
    }
}
