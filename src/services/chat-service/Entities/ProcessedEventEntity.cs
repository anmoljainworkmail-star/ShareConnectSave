using MongoDB.Bson.Serialization.Attributes;

namespace chat_service.Entities;

// Idempotency ledger (kafka-outbox skill) - the Mongo-side equivalent of
// connection-service's/discovery-service's `processed_events` SQL table
// (see com.shareconnectsave.connection.kafka.domain.ProcessedEvent). This is
// chat-service's FIRST Kafka consumer (T038), and this service has no SQL
// store at all (Mongo is its only database, same reasoning as
// ChatRoomEntity's Mongo-native outbox fields) - so "one row per processed
// event_id" becomes "one document per processed event_id" here instead.
//
// EventId doubles as Mongo's own `_id`, not a separate field with its own
// generated ObjectId. That is what makes "has this event already been
// processed" a single indexed existence check (Mongo's default _id index is
// always unique), and - just as importantly - what makes a second, racing
// insert of the SAME event_id fail with a duplicate-key error at the
// DATABASE level rather than silently succeeding twice. This is the same
// "uniqueness enforced by the storage engine, not just by this service's own
// check-then-act code" guarantee a SQL PRIMARY KEY gives the Java consumers'
// version of this table.
public sealed class ProcessedEventEntity
{
    [BsonId]
    public string EventId { get; set; } = null!;

    [BsonElement("processed_at")]
    public DateTime ProcessedAt { get; set; }
}
