using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace chat_service.Entities;

// Naming note (CLAUDE.md Java Rules call for an explicit "Entity" suffix on
// JPA @Entity classes going forward; that rule is Java-only, but the same
// intent applies here by convention, not mandate): this class is named
// ChatRoomEntity, not ChatRoom, purely so it stays visually distinct from any
// future DTO of the same concept (e.g. a ChatRoomResponse a later ticket's
// controller might return) - there is no separate DTO yet in this ticket.
//
// [BsonElement] attributes are the hard requirement here, not the class name:
// the T007 mongo-init script (src/infra/mongo-init/01-init-chat.js) and this
// service must agree on the literal snake_case field names written to the
// wire, or documents one side writes won't read correctly on the other -
// MongoDB has no schema to enforce this for us, so the attributes ARE the
// contract.
public sealed class ChatRoomEntity
{
    // [BsonId] marks this property as Mongo's _id - BsonRepresentation lets
    // the field stay a normal ObjectId on the wire while this class exposes
    // it as a string, so repository callers never need to reference
    // MongoDB.Bson.ObjectId directly.
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    [BsonElement("connection_id")]
    public string ConnectionId { get; set; } = null!;

    [BsonElement("user_a_id")]
    public long UserAId { get; set; }

    [BsonElement("user_b_id")]
    public long UserBId { get; set; }

    [BsonElement("status")]
    public string Status { get; set; } = null!;

    [BsonElement("created_at")]
    public DateTime CreatedAt { get; set; }

    [BsonElement("closed_at")]
    public DateTime? ClosedAt { get; set; }

    // T036 addition (not part of T007's original schema, but Mongo is
    // schemaless so no migration/init-script change is needed to add a
    // field going forward): per-user "Met Successfully" confirmation.
    //
    // Why two separate nullable timestamps instead of one bool or one
    // "MetByUserId" field - the domain rule (REQUIREMENTS.md / CLAUDE.md's
    // responsibility boundary) is "the chat closes once BOTH users confirm",
    // which is inherently a two-party fact. A single field could only ever
    // record the LAST caller, silently losing the first confirmer's
    // timestamp - these two fields are what let ChatService answer "has
    // each side confirmed, and when" without any extra state.
    //
    // T037 (not yet built) owns the actual OPEN -> CLOSING -> CLOSED state
    // machine and grace timer that these two fields will drive; this ticket
    // only records the confirmation, it deliberately does not transition
    // Status itself.
    [BsonElement("user_a_met_at")]
    public DateTime? UserAMetAt { get; set; }

    [BsonElement("user_b_met_at")]
    public DateTime? UserBMetAt { get; set; }
}
