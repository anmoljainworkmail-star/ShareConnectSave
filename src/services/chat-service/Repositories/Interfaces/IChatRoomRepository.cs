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
}
