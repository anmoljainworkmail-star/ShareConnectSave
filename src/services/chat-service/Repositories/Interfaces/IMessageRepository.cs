using chat_service.Entities;

namespace chat_service.Repositories.Interfaces;

// Pattern: Repository + Interface Segregation (SOLID I) - see
// IChatRoomRepository's comment for the full reasoning. This interface is
// kept narrow and separate from IChatRoomRepository: a class that only ever
// sends/reads messages (e.g. the future SignalR hub) depends on this
// interface alone, not on chat-room lifecycle methods it has no reason to
// call.
//
// Deliberately NO "get message history" method here - REQUIREMENTS.md is
// explicit that no history endpoint exists by design (the TTL index is what
// makes that true at the data layer; this repository's shape is what makes
// it true at the code layer too).
public interface IMessageRepository
{
    Task CreateAsync(MessageEntity message);
}
