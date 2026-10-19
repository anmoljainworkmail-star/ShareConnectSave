package com.shareconnectsave.rating.chat;

// Dependency Inversion (SOLID-D): RatingServiceImpl depends on THIS
// interface, never on whichever implementation currently backs the "is this
// chat closed" check. Liskov Substitution (SOLID-L): T042's since-removed
// always-true placeholder and T044's real
// ClosedChatBackedChatClosureChecker (reading the chat.closed-populated
// closed_chats table) were swappable with zero change to RatingServiceImpl -
// exactly the guarantee this seam was built for.
//
// Resolved ticket-sequencing gap: T042 shipped against
// PlaceholderChatClosureChecker (always true, loudly logged) because the
// chat.closed consumer and closed_chats table did not exist yet. T044 is
// what closes that gap - see ClosedChatBackedChatClosureChecker.
public interface ChatClosureChecker {

    boolean isChatClosed(Long connectionId);
}
