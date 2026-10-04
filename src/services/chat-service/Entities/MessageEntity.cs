using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace chat_service.Entities;

// See ChatRoomEntity's class comment for why this is named MessageEntity and
// why the [BsonElement] names below are a hard wire-format contract shared
// with T007's mongo-init script, not a style choice.
public sealed class MessageEntity
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    [BsonElement("chat_id")]
    public string ChatId { get; set; } = null!;

    [BsonElement("sender_id")]
    public long SenderId { get; set; }

    [BsonElement("content")]
    public string Content { get; set; } = null!;

    // This field is the TTL anchor: MongoIndexInitializer creates an
    // expireAfterSeconds index on exactly this field (sent_at), so a
    // document's lifetime is measured from when it was sent, not from when
    // it happened to be read or written by some other process.
    [BsonElement("sent_at")]
    public DateTime SentAt { get; set; }
}
