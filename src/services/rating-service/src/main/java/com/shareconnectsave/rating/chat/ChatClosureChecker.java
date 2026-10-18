package com.shareconnectsave.rating.chat;

// Dependency Inversion (SOLID-D): RatingServiceImpl depends on THIS
// interface, never on whichever implementation currently backs the "is this
// chat closed" check. Liskov Substitution (SOLID-L): this ticket's
// always-true placeholder and T044's real chat.closed-consumer-backed
// implementation must be swappable with zero change to any caller.
//
// Ticket-sequencing gap (Tech Lead-flagged, same shape as T031's own
// "OutboxRelay doesn't exist yet" gap): the chat.closed Kafka consumer that
// would populate a local closed_chats table is T044's job, a later ticket.
// T042 depends only on T041, so no real data source to check against exists
// yet. This interface exists now so the 400 CHAT_NOT_CLOSED path has a real
// seam in the code - T044 only has to swap the implementation bound here,
// never touch RatingServiceImpl or add new wiring.
public interface ChatClosureChecker {

    boolean isChatClosed(Long connectionId);
}
