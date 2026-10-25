package com.shareconnectsave.connection;

import com.shareconnectsave.connection.domain.ConnectionCreatedResponse;
import com.shareconnectsave.connection.domain.ConnectionResponse;
import com.shareconnectsave.connection.domain.CreateConnectionDto;

import java.util.List;
import java.util.Optional;

// Dependency Inversion (SOLID-D): ConnectionController depends on THIS
// interface, never on ConnectionServiceImpl directly — same shape as
// discovery-service's ScanSessionService / ScanQueryService pair. Whichever
// @Service bean Spring wires in behind it, the controller never has to
// change, and a unit test of the controller can substitute a hand-written
// fake with no Spring context at all.
public interface ConnectionService {

    ConnectionCreatedResponse createConnection(Long requesterId, CreateConnectionDto dto);

    ConnectionResponse acceptConnection(Long connectionId, Long callerId);

    ConnectionResponse declineConnection(Long connectionId, Long callerId);

    List<ConnectionResponse> getPendingInbound(Long userId);

    Optional<ConnectionResponse> getActiveConnection(Long userId);

    // Single Responsibility (SOLID-S): ConnectionExpiryScheduler only owns
    // the "when" (fixedRate = 60000); everything about the "what" — which
    // requests qualify as overdue — is this interface's job, same split
    // OutboxRelay/IOutboxService already uses. Deliberately returns only
    // ids, not entities: the scheduler has no business reading (let alone
    // holding open) domain objects, it only needs something to hand back
    // to expireConnectionRequest one at a time.
    List<Long> findOverdueConnectionRequestIds();

    // Split out from findOverdueConnectionRequestIds as its OWN interface
    // method — not a private helper on ConnectionServiceImpl — for a
    // Spring-specific reason: @Transactional only takes effect on a call
    // that arrives through the Spring-generated proxy around this bean.
    // A private method called via `this.expireConnectionRequest(id)` from
    // inside another method of the SAME class (self-invocation) skips that
    // proxy entirely, so the per-request transaction boundary the ticket
    // requires would silently stop existing — the status update and the
    // outbox row could then commit as two separate auto-committed writes
    // instead of one atomic unit, quietly breaking the Outbox Pattern's
    // guarantee for exactly this code path. Making this a real, separate,
    // externally-callable interface method — invoked by
    // ConnectionExpiryScheduler through its injected ConnectionService bean,
    // never by this class calling itself — is what keeps each iteration a
    // genuine, isolated transaction: one bad row can throw, roll back on
    // its own, and the loop's next iteration is unaffected.
    void expireConnectionRequest(Long connectionId);

    // T104 — Single Responsibility (SOLID-S): AdminConnectionController only
    // owns the "is this caller allowed to do this at all" question
    // (X-User-Role: admin); everything about WHICH rows qualify and how many
    // were changed is this interface's job, same split
    // expireConnectionRequest/ConnectionExpiryScheduler already uses above.
    // Deliberately NOT built on top of expireConnectionRequest: that method's
    // own "status != PENDING -> no-op" guard exists for TTL-sweep idempotency
    // (a row the scheduler's earlier read already saw move on), and would
    // silently skip every ACCEPTED connection — exactly the case an admin
    // suspend needs to clear. This method also intentionally does NOT give
    // each row its own @Transactional boundary the way the scheduler's loop
    // does: the scheduler needed per-row isolation because it calls this bean
    // many times across one tick and one bad row must not roll back the
    // others, but here the ticket calls for one atomic bulk operation for one
    // user — see ConnectionServiceImpl for where that single transaction
    // boundary is drawn.
    int expireActiveConnectionsForUser(Long userId);

    // T107 — Single Responsibility (SOLID-S): AdminConnectionController only
    // owns "is this caller allowed to see platform stats at all" (the
    // X-User-Role guard); everything about WHAT counts as "accepted today"
    // is this interface's job, same split every other method on this
    // interface already uses for its own controller. Deliberately takes NO
    // parameters — "today" is computed inside the implementation, exactly
    // once, from the system clock — rather than making the controller reach
    // for LocalDate.now(ZoneOffset.UTC) itself; a thin controller only
    // translates HTTP in/out (see AdminConnectionController's own class
    // comment), it was never the right place to decide what "today" means.
    int countConnectionsAcceptedToday();
}
