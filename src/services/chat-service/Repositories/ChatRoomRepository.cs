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
}
