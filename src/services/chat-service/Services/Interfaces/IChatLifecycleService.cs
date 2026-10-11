namespace chat_service.Services.Interfaces;

// Single Responsibility (SOLID S): IChatService (see that interface's own
// comment) owns the per-request rules around messages and recording a
// single user's "met" confirmation. IChatLifecycleService owns a narrower,
// different concern - the ROOM STATE MACHINE side effect that fires only
// once BOTH users have confirmed: starting the OPEN -> CLOSING grace timer.
// ChatController depends on both interfaces for the one /chats/:id/met
// route specifically because those are two genuinely separate
// responsibilities, not because this ticket is splitting hairs - see
// ChatLifecycleService's own comment for why it composes IChatService rather
// than duplicating its guard clauses (not-found / not-a-participant /
// already-closed).
//
// Dependency Inversion (SOLID D): ChatController asks the DI container for
// this interface, never for the concrete ChatLifecycleService.
public interface IChatLifecycleService
{
    // Reuses IChatService's own MarkMetOutcome Result Object (defined
    // alongside IChatService) - the HTTP response shape this produces is
    // IDENTICAL to what IChatService.MarkMetSuccessfullyAsync alone would
    // have returned; this method only adds a side effect (starting the
    // grace timer) when BothUsersMet is true, it never changes what the
    // caller sees.
    Task<MarkMetOutcome> MarkMetSuccessfullyAsync(string chatId, long userId);
}
