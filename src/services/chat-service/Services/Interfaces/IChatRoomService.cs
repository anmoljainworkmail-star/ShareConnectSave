using chat_service.Entities;

namespace chat_service.Services.Interfaces;

// Single Responsibility (SOLID S): this interface's only job is "open a new
// chat room for an accepted connection" - it has no opinion on WHETHER one
// should be opened right now (ConnectionAcceptedHandler's processed_events
// check already answered that before calling this), and it does not close,
// message, or transition a room (ICloseChatRoomService/IChatService own
// those). Keeping this narrow is what lets ConnectionAcceptedHandler stay a
// thin coordinator (dotnet-mvc-controllers skill's service-layer convention)
// instead of calling IChatRoomRepository.CreateAsync directly and building
// the ChatRoomEntity itself.
//
// Dependency Inversion (SOLID D): ConnectionAcceptedHandler asks the DI
// container for this interface, never for the concrete ChatRoomService or
// for IChatRoomRepository directly.
public interface IChatRoomService
{
    // requesterId/recipientId map onto ChatRoomEntity's UserAId/UserBId
    // respectively - an arbitrary but deterministic assignment (neither
    // field means anything more than "one of the two participants"
    // elsewhere in this service, see ChatService.MarkMetSuccessfullyAsync's
    // own isUserA resolution), so this is the one place that mapping is
    // decided for a freshly-opened room.
    Task<ChatRoomEntity> OpenRoomAsync(long connectionId, long requesterId, long recipientId);
}
