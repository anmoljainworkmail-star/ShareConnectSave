package com.shareconnectsave.rating.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Liskov Substitution (SOLID-L): this is the real implementation
// PlaceholderChatClosureChecker's own class comment (T042) promised - RatingServiceImpl
// never changed, never learned this class exists, and never will need to:
// it still depends on nothing but the ChatClosureChecker interface. Swapping
// the always-true placeholder for a real, data-backed answer is a pure
// composition-root change (one @Component replacing another), which is
// exactly what depending on an interface instead of a concrete class buys
// you.
//
// Dependency Inversion (SOLID-D): this class depends on ClosedChatRepository
// (an abstraction over "where closed-chat facts are stored"), not on
// ChatClosedConsumer directly - it has no idea a Kafka consumer is what
// populates the table it reads. The two communicate only through the
// database, same "write side / read side" split OutboxServiceImpl and
// OutboxRelay already use for the outbox table.
@Component
@RequiredArgsConstructor
public class ClosedChatBackedChatClosureChecker implements ChatClosureChecker {

    private final ClosedChatRepository closedChatRepository;

    @Override
    public boolean isChatClosed(Long connectionId) {
        return closedChatRepository.existsByConnectionId(connectionId);
    }
}
