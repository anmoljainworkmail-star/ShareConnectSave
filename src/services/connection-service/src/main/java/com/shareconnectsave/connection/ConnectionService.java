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
}
