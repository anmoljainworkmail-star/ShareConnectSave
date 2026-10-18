package com.shareconnectsave.rating.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Honest placeholder (Tech Lead decision documented on T042): rating-service
// has no chat.closed consumer or closed_chats table yet - that is T044's
// job, a later ticket, and T042 depends only on T041. Two options were
// weighed here: throw UnsupportedOperationException (safe in the sense of
// never lying, but it would also block EVERY rating submission - including
// the ticket's own "valid rating -> persisted" acceptance criterion -
// making the whole endpoint unusable until T044 ships), or return true
// unconditionally (lets every acceptance criterion this ticket actually
// needs to pass keep working, at the honestly-documented cost of not yet
// enforcing the "chat must be closed" rule for real).
//
// Returning true was chosen as the lesser risk: it keeps this ticket's
// scope (the endpoint, its validation shape, the outbox write) deliverable
// and testable without silently pretending the check is real - the loud
// log line below is what keeps that gap visible to anyone watching this
// service's logs until T044 replaces this class entirely.
@Component
@Slf4j
public class PlaceholderChatClosureChecker implements ChatClosureChecker {

    @Override
    public boolean isChatClosed(Long connectionId) {
        log.warn("ChatClosureChecker placeholder in effect (T044 not yet shipped) - "
                + "allowing rating submission for connectionId={} without a real "
                + "chat-closed check. Replace PlaceholderChatClosureChecker once the "
                + "chat.closed consumer and closed_chats table exist.", connectionId);
        return true;
    }
}
