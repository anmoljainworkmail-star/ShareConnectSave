package com.shareconnectsave.rating.chat;

import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository - plain JpaRepository, no "I" prefix (see
// RatingRepository's own comment for the full project-wide convention this
// follows).
//
// existsByConnectionId backs TWO call sites: ChatClosedConsumer's own
// insert-guard (redelivery safety, defense in depth alongside the
// processed_events idempotency check) and
// ClosedChatBackedChatClosureChecker's real "is this chat closed" answer -
// both only ever need a boolean, never the row itself.
public interface ClosedChatRepository extends JpaRepository<ClosedChat, Long> {

    boolean existsByConnectionId(Long connectionId);
}
