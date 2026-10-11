namespace chat_service.Services.Interfaces;

// Single Responsibility (SOLID S): this interface's only job is "what
// happens when a room closes" - delete its messages, enqueue chat.closed,
// flip Status to CLOSED. It has no opinion on WHY a room is closing; that
// decision lives entirely in its two callers (ChatLifecycleService's grace
// timer, and ChatAutoCloseHostedService's 2-hour poll). This is the same
// "shared routine, not duplicated logic" reasoning CLAUDE.md's Outbox row
// gives for IOutboxService being the one abstraction every Kafka producer in
// this codebase depends on (Dependency Inversion, SOLID D) - here, it's the
// one abstraction every CLOSE TRIGGER depends on, so "what closing means"
// can only ever be defined in one place.
public interface ICloseChatRoomService
{
    // closedReason must be one of ChatClosedReason's constants - it flows
    // straight into the enqueued chat.closed payload's closed_reason field
    // (see contracts/kafka/chat.closed.schema.json's enum). Idempotent by
    // design: calling this twice for the same already-CLOSED room is a
    // logged no-op, not an error - both callers can legitimately race to
    // close the same room (see ChatRoomRepository.CloseRoomAsync's filter).
    Task CloseRoomAsync(string chatId, string closedReason);
}
