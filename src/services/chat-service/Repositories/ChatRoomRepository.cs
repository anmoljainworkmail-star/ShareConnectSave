using chat_service.Entities;
using chat_service.Repositories.Interfaces;
using MongoDB.Driver;

namespace chat_service.Repositories;

// Concrete Mongo implementation of IChatRoomRepository - see that interface
// for the Repository/Interface Segregation/Dependency Inversion reasoning.
// This class's only job is translating repository calls into
// IMongoCollection<ChatRoomEntity> operations; it holds no business rules.
public sealed class ChatRoomRepository : IChatRoomRepository
{
    private readonly IMongoCollection<ChatRoomEntity> _chatRooms;

    public ChatRoomRepository(IMongoDatabase database)
    {
        // Collection name is the literal "chat_rooms" string the T007
        // mongo-init script already created - this is the one line in this
        // class that ties the C# model to that collection, intentionally
        // kept here rather than scattered across every method.
        _chatRooms = database.GetCollection<ChatRoomEntity>("chat_rooms");
    }

    public async Task<ChatRoomEntity?> FindByConnectionIdAsync(string connectionId)
    {
        return await _chatRooms
            .Find(room => room.ConnectionId == connectionId)
            .FirstOrDefaultAsync();
    }

    public async Task<ChatRoomEntity?> FindByIdAsync(string chatId)
    {
        return await _chatRooms
            .Find(room => room.Id == chatId)
            .FirstOrDefaultAsync();
    }

    public async Task CreateAsync(ChatRoomEntity room)
    {
        await _chatRooms.InsertOneAsync(room);
    }

    public async Task<ChatRoomEntity?> MarkUserMetSuccessfullyAsync(string chatId, bool isUserA, DateTime metAt)
    {
        // FindOneAndUpdateAsync (atomic single-document update): setting
        // only the one field that changed, rather than reading the whole
        // document into C#, mutating it, and writing the whole thing back,
        // is what makes this update race-safe against a second concurrent
        // write to the OTHER user's field landing in between - two near-
        // simultaneous "met" calls (one per user) can never clobber each
        // other's confirmation, because each touches a different field via
        // its own atomic command.
        var update = isUserA
            ? Builders<ChatRoomEntity>.Update.Set(r => r.UserAMetAt, metAt)
            : Builders<ChatRoomEntity>.Update.Set(r => r.UserBMetAt, metAt);

        return await _chatRooms.FindOneAndUpdateAsync<ChatRoomEntity>(
            room => room.Id == chatId,
            update,
            new FindOneAndUpdateOptions<ChatRoomEntity> { ReturnDocument = ReturnDocument.After });
    }

    public async Task<ChatRoomEntity?> TransitionToClosingAsync(string chatId)
    {
        var update = Builders<ChatRoomEntity>.Update.Set(r => r.Status, ChatRoomStatus.CLOSING);

        // Filtering on room.Status == OPEN (not just room.Id == chatId) is
        // the whole mechanism here - see IChatRoomRepository's comment. Two
        // callers racing to flip the same room: at most one filter matches,
        // because MongoDB evaluates and applies a FindOneAndUpdate filter
        // atomically against the document as it exists at that instant.
        return await _chatRooms.FindOneAndUpdateAsync<ChatRoomEntity>(
            room => room.Id == chatId && room.Status == ChatRoomStatus.OPEN,
            update,
            new FindOneAndUpdateOptions<ChatRoomEntity> { ReturnDocument = ReturnDocument.After });
    }

    public async Task<IReadOnlyList<ChatRoomEntity>> FindOpenRoomsOlderThanAsync(DateTime openedBeforeUtc)
    {
        return await _chatRooms
            .Find(room => room.Status == ChatRoomStatus.OPEN && room.CreatedAt < openedBeforeUtc)
            .ToListAsync();
    }

    public async Task<ChatRoomEntity?> CloseRoomAsync(
        string chatId,
        DateTime closedAtUtc,
        string outboxEventType,
        string outboxPayload,
        DateTime outboxCreatedAtUtc)
    {
        // Mongo-native Outbox Pattern (Tech Lead architecture decision, see
        // ChatRoomEntity's and CloseChatRoomService's comments for the full
        // reasoning): Combine(...) bundles six Set operations into one
        // update document, which FindOneAndUpdateAsync then applies as a
        // SINGLE atomic Mongo command. That single command is what gives
        // this Mongo-only service the identical guarantee a relational
        // Outbox table gets from one SQL transaction - "room is CLOSED" and
        // "chat.closed is durably enqueued" can never land as only one of
        // the two; they are literally the same write.
        var update = Builders<ChatRoomEntity>.Update.Combine(
            Builders<ChatRoomEntity>.Update.Set(r => r.Status, ChatRoomStatus.CLOSED),
            Builders<ChatRoomEntity>.Update.Set(r => r.ClosedAt, closedAtUtc),
            Builders<ChatRoomEntity>.Update.Set(r => r.OutboxEventType, outboxEventType),
            Builders<ChatRoomEntity>.Update.Set(r => r.OutboxPayload, outboxPayload),
            Builders<ChatRoomEntity>.Update.Set(r => r.OutboxCreatedAt, outboxCreatedAtUtc),
            Builders<ChatRoomEntity>.Update.Set(r => r.OutboxPublishedAt, (DateTime?)null));

        // Status != Closed guard (idempotency at the database level, not
        // just in CloseChatRoomService's C#): if the grace timer and the
        // auto-close poller both reach this call for the same room, only
        // the first write's filter matches - the second gets null back and
        // the caller treats that as "already closed, no-op" rather than
        // re-enqueueing a second chat.closed event with a new EventId.
        return await _chatRooms.FindOneAndUpdateAsync<ChatRoomEntity>(
            room => room.Id == chatId && room.Status != ChatRoomStatus.CLOSED,
            update,
            new FindOneAndUpdateOptions<ChatRoomEntity> { ReturnDocument = ReturnDocument.After });
    }

    public async Task<IReadOnlyList<ChatRoomEntity>> FindPendingOutboxRoomsAsync(int batchSize)
    {
        // Sort by OutboxCreatedAt ascending + Limit(batchSize): same
        // oldest-first, bounded-batch shape as connection-service's
        // OutboxRepository.findByStatusOrderByCreatedAtAsc(PENDING,
        // PageRequest.of(0, BATCH_SIZE)) - oldest-first so one slow/stuck
        // event can't starve newer ones indefinitely, bounded so one poll
        // tick can't try to hand an unbounded result set to Kafka at once.
        return await _chatRooms
            .Find(room => room.OutboxEventType != null && room.OutboxPublishedAt == null)
            .SortBy(room => room.OutboxCreatedAt)
            .Limit(batchSize)
            .ToListAsync();
    }

    public async Task<ChatRoomEntity?> MarkOutboxPublishedAsync(string chatId, DateTime publishedAtUtc)
    {
        var update = Builders<ChatRoomEntity>.Update.Set(r => r.OutboxPublishedAt, publishedAtUtc);

        // OutboxPublishedAt == null guard - see IChatRoomRepository's comment:
        // this is what keeps two relay replicas (or two overlapping ticks of
        // the same relay) from both believing they were the one that marked
        // this event published.
        return await _chatRooms.FindOneAndUpdateAsync<ChatRoomEntity>(
            room => room.Id == chatId && room.OutboxPublishedAt == null,
            update,
            new FindOneAndUpdateOptions<ChatRoomEntity> { ReturnDocument = ReturnDocument.After });
    }
}
