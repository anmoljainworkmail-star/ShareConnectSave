using chat_service.Entities;

namespace chat_service.Repositories.Interfaces;

// Pattern: Repository + Interface Segregation (SOLID I) - see
// IChatRoomRepository's comment for the full reasoning. This interface is
// kept narrow and separate from IChatRoomRepository: a class that only ever
// sends/reads messages (e.g. the future SignalR hub) depends on this
// interface alone, not on chat-room lifecycle methods it has no reason to
// call.
//
// T035 deliberately shipped with NO "get message history" method -
// REQUIREMENTS.md's "no history endpoint exists by design" refers to a
// PERMANENT history/export endpoint (the TTL index is what makes that true
// at the data layer). T036 adds FindRecentByChatIdAsync below, which is a
// different thing entirely: a small, bounded window (max 50, see
// ChatService's cap) of messages that still exist only because the TTL
// hasn't deleted them yet - a chat screen needs this to render "what has
// been said so far in the still-open conversation", which is not the same
// promise as a durable export/archive. The ephemerality guarantee is
// unchanged: this method can only ever return messages the TTL index has
// not yet reaped, same as any other read of this collection.
public interface IMessageRepository
{
    Task CreateAsync(MessageEntity message);

    // Returns at most `limit` of the MOST RECENT messages for a chat,
    // ordered ascending by sent_at (oldest of the returned window first) -
    // see MessageRepository's implementation comment for why that ordering
    // is applied in two steps rather than one.
    Task<IReadOnlyList<MessageEntity>> FindRecentByChatIdAsync(string chatId, int limit);
}
