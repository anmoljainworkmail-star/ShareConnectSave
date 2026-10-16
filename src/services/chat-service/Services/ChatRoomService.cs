using chat_service.Entities;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;

namespace chat_service.Services;

// See IChatRoomService's comment for the Single Responsibility/Dependency
// Inversion reasoning. This class's only caller is
// ConnectionAcceptedHandler (T038) - REQUIREMENTS.md/CLAUDE.md's Event-Driven
// Architecture principle ("services react to things that happened, they are
// not commanded") is why there is deliberately no HTTP route or hub method
// that can reach this method: a chat room can only ever come into existence
// because a connection.accepted Kafka event said so.
public class ChatRoomService : IChatRoomService
{
    private readonly IChatRoomRepository _chatRoomRepository;
    private readonly ILogger<ChatRoomService> _logger;

    public ChatRoomService(IChatRoomRepository chatRoomRepository, ILogger<ChatRoomService> logger)
    {
        _chatRoomRepository = chatRoomRepository;
        _logger = logger;
    }

    public async Task<ChatRoomEntity> OpenRoomAsync(long connectionId, long requesterId, long recipientId)
    {
        // Idempotency guard (Saga + Choreography pattern): if a room already
        // exists for this connection, return it instead of creating a second
        // one. This handles the case where Kafka delivers connection.accepted
        // twice (e.g. consumer crash after room-creation but before offset
        // commit), or a race between two consumer instances. Combined with the
        // database-level unique index on connection_id, this two-layer check
        // (application + database) is what makes "at most one room per
        // connection" a guaranteed invariant.
        var connectionIdStr = connectionId.ToString();
        var existingRoom = await _chatRoomRepository.FindByConnectionIdAsync(connectionIdStr);
        if (existingRoom != null)
        {
            _logger.LogInformation(
                "Chat room {ChatId} already exists for connection {ConnectionId}, reusing.",
                existingRoom.Id, connectionId);
            return existingRoom;
        }

        var room = new ChatRoomEntity
        {
            // ChatRoomEntity.ConnectionId is a string (see that class's own
            // comment on why - Mongo has no opinion on the type, this
            // service just never needed it as anything but an equality key),
            // while connection.accepted's connection_id is a long on the
            // wire (Connection Service's real SQL Server BIGINT id) - this
            // ToString() is the one place that conversion happens.
            ConnectionId = connectionIdStr,
            UserAId = requesterId,
            UserBId = recipientId,
            Status = ChatRoomStatus.OPEN,

            // CreatedAt is set to NOW (when THIS service actually creates the
            // document), not the event's own AcceptedAt timestamp - see
            // ChatRoomEntity's comment: CreatedAt doubles as "opened at" and
            // directly drives ChatAutoCloseHostedService's 2-hour-open
            // threshold query. Measuring that threshold from when the room
            // actually started existing (not from whenever Connection
            // Service originally published the event, which could be
            // seconds or, under consumer lag, much longer before this line
            // runs) is what keeps "2 hours open" an accurate promise.
            CreatedAt = DateTime.UtcNow,
        };

        try
        {
            await _chatRoomRepository.CreateAsync(room);
        }
        catch (MongoDB.Driver.MongoWriteException ex) when (ex.WriteError?.Category == MongoDB.Driver.ServerErrorCategory.DuplicateKey)
        {
            // Database-level idempotency backstop: this catch fires only if a
            // concurrent consumer instance created a room for this same
            // connection between our FindByConnectionIdAsync check above and
            // this InsertOneAsync call. Fetch and return that room instead of
            // propagating the exception - the end result is the same: a room
            // exists for this connection, both users get notified, and this
            // handler records the event as processed.
            var existingRoomAfterRace = await _chatRoomRepository.FindByConnectionIdAsync(connectionIdStr);
            if (existingRoomAfterRace != null)
            {
                _logger.LogInformation(
                    "Duplicate-key race detected: chat room {ChatId} created concurrently for connection {ConnectionId}, reusing.",
                    existingRoomAfterRace.Id, connectionId);
                return existingRoomAfterRace;
            }

            // Unexpected state - unique index violation, but we can't find the
            // room. This should not happen in practice, but propagating the
            // original exception is safer than silently continuing.
            throw;
        }

        _logger.LogInformation(
            "Opened chat room {ChatId} for connection {ConnectionId} (users {UserAId}, {UserBId}).",
            room.Id, connectionId, requesterId, recipientId);

        return room;
    }
}
