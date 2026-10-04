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

    Task CreateAsync(ChatRoomEntity room);
}
