using chat_service.Entities;

namespace chat_service.Repositories.Interfaces;

// Pattern: Repository - hides "chat rooms live in a Mongo collection called
// chat_rooms" behind a small, collection-like interface. Callers (a future
// controller or SignalR hub) ask for a room by connection id; they never
// build an IMongoCollection<ChatRoomEntity> filter themselves.
//
// Pattern: Interface Segregation (SOLID I) - this interface only exposes
// chat-room operations, deliberately NOT combined with IMessageRepository
// into one IChatRepository. A class that only ever needs to look up or open
// a room (e.g. a future "has this connection already got a room" check)
// shouldn't be forced to depend on message-sending methods it never calls.
//
// Pattern: Dependency Inversion (SOLID D) - Program.cs is the only place
// that binds this interface to ChatRoomRepository (the concrete Mongo
// implementation); every other class in this service depends on
// IChatRoomRepository only, so an in-memory fake can stand in for tests
// without touching caller code.
public interface IChatRoomRepository
{
    Task<ChatRoomEntity?> FindByConnectionIdAsync(string connectionId);

    // T036 addition: ChatHub/ChatController both look a room up by its own
    // id (the chat_id a client already holds, e.g. from a future T038
    // ChatOpened notification), not by the connection it was created from -
    // a different access pattern than FindByConnectionIdAsync above, so it
    // is its own method rather than overloading that one.
    Task<ChatRoomEntity?> FindByIdAsync(string chatId);

    Task CreateAsync(ChatRoomEntity room);

    // T036 addition: records ONE side's "Met Successfully" confirmation and
    // returns the room as it stands immediately after that write, so the
    // caller (ChatService) can check both UserAMetAt/UserBMetAt without a
    // second round trip. `isUserA` is resolved by the CALLER (ChatService
    // already fetched the room once to validate the caller is a
    // participant) rather than this method re-deriving it from userId -
    // that keeps "which field does this user map to" decided in exactly one
    // place.
    Task<ChatRoomEntity?> MarkUserMetSuccessfullyAsync(string chatId, bool isUserA, DateTime metAt);

    // T037 addition: atomic OPEN -> CLOSING transition, guarded by the
    // room's CURRENT status in the filter (not read-then-write in C#) - the
    // same optimistic-concurrency-flavored guarantee RowVersion gives a SQL
    // UPDATE ... WHERE RowVersion = @expected (see this project's own
    // rowversion-optimistic-concurrency-concept notes), just expressed as a
    // Mongo filter instead of a column. Returns null if the room was NOT
    // still OPEN at the instant Mongo evaluated the filter (already
    // CLOSING/CLOSED, or never existed) - callers treat null as "someone
    // else already handled this, do not start a second grace timer."
    Task<ChatRoomEntity?> TransitionToClosingAsync(string chatId);

    // T037 addition: the auto-close poller's query. CreatedAt doubles as
    // "opened at" (see ChatRoomEntity's comment on why there is no separate
    // OpenedAtUtc field) - a room is only ever created already OPEN.
    Task<IReadOnlyList<ChatRoomEntity>> FindOpenRoomsOlderThanAsync(DateTime openedBeforeUtc);

    // T037 addition: the single atomic write that closes a room - see
    // CloseChatRoomService's comment for the full Mongo-native Outbox
    // Pattern reasoning. Status/ClosedAt and all four outbox fields are set
    // together via Builders<T>.Update.Combine(...) in ONE
    // FindOneAndUpdateAsync call, filtered on Status != CLOSED so a second,
    // racing close trigger (grace timer vs. auto-close poll) gets null back
    // instead of silently overwriting an already-enqueued outbox entry.
    Task<ChatRoomEntity?> CloseRoomAsync(
        string chatId,
        DateTime closedAtUtc,
        string outboxEventType,
        string outboxPayload,
        DateTime outboxCreatedAtUtc);

    // T039 addition: OutboxRelayHostedService's own query. This is the Mongo
    // equivalent of connection-service's
    // OutboxRepository.findByStatusOrderByCreatedAtAsc(PENDING, ...) - "find
    // rows still waiting to be relayed to Kafka" - except here a "row" IS a
    // chat_rooms document (see ChatRoomEntity's Mongo-native embedded Outbox
    // Pattern comment), not a separate outbox table. OutboxEventType != null
    // excludes rooms that were never closed yet (CloseRoomAsync is the only
    // writer of that field); OutboxPublishedAt == null is this design's
    // PENDING marker, same role Java's OutboxStatus.PENDING enum value plays.
    Task<IReadOnlyList<ChatRoomEntity>> FindPendingOutboxRoomsAsync(int batchSize);

    // T039 addition: the relay's "mark processed" write. Filtering on
    // OutboxPublishedAt == null (not just Id == chatId) is what makes this
    // safe to call from more than one chat-service replica's relay polling
    // at the same moment - at most one replica's FindOneAndUpdateAsync
    // filter matches, so the same chat.closed event is never double-marked-
    // published (though it may still be sent to Kafka twice under a crash
    // between send and mark; that is exactly why Rating/Notification Service
    // consumers must dedupe on event_id, same as every other at-least-once
    // Kafka consumer in this project).
    Task<ChatRoomEntity?> MarkOutboxPublishedAsync(string chatId, DateTime publishedAtUtc);
}
