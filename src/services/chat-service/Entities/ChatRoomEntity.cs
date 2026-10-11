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

    // [BsonRepresentation(BsonType.String)] is what makes this a real,
    // compile-time-checked enum (see ChatRoomStatus's own comment) while
    // still writing/reading the wire field as the plain string "OPEN" /
    // "CLOSING" / "CLOSED" - the same text format the T007 mongo-init
    // script and any `mongosh`/Compass inspection already expect.
    [BsonElement("status")]
    [BsonRepresentation(BsonType.String)]
    public ChatRoomStatus Status { get; set; }

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

    // T037 additions below - Mongo-native embedded Outbox Pattern (Tech Lead
    // architecture decision, see T037 ticket addendum). Every other Outbox
    // producer in this codebase (user-service, connection-service,
    // discovery-service) writes its business row and its outbox row together
    // in ONE SQL Server transaction. Chat Service has no SQL schema at all -
    // Mongo is its only store - and a separate `outbox` collection would need
    // a multi-document Mongo transaction to get that same durability
    // guarantee, which in turn requires Mongo running as a replica set (this
    // project's dev Mongo is a single standalone node). Folding exactly ONE
    // pending outbox event directly onto this same document sidesteps that
    // entirely: CloseChatRoomService sets these four fields AND Status AND
    // ClosedAt in a single FindOneAndUpdateAsync call (see that class and
    // ChatRoomRepository.CloseRoomAsync), so it is impossible for a room to
    // end up CLOSED without its matching chat.closed event also being
    // durably written, or vice versa - both land in the same atomic command.
    // A future ticket (T039) polls for OutboxPublishedAt == null and does the
    // actual Kafka send; that relay step is explicitly out of scope here.
    [BsonElement("outbox_event_type")]
    public string? OutboxEventType { get; set; }

    [BsonElement("outbox_payload")]
    public string? OutboxPayload { get; set; }

    [BsonElement("outbox_created_at")]
    public DateTime? OutboxCreatedAt { get; set; }

    // Null means "not yet relayed to Kafka" - T039's BackgroundService will
    // poll for exactly this condition. This ticket only ever writes this
    // field as null (enqueueing); nothing in T037 ever sets it to non-null.
    [BsonElement("outbox_published_at")]
    public DateTime? OutboxPublishedAt { get; set; }

    // Deliberately NOT adding a separate OpenedAtUtc field here even though
    // the ticket's notes suggest one: CreatedAt (above) already IS the
    // "opened at" timestamp, because a room only ever starts existing in the
    // OPEN state (T038 creates it that way, on connection.accepted) - there
    // is no distinct "opened later than created" moment to record. Adding a
    // second field with the same value would duplicate state for no reason;
    // ChatRoomRepository.FindOpenRoomsOlderThanAsync below filters on
    // CreatedAt for the 2-hour auto-close check.
}
