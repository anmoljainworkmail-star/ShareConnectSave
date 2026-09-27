package com.shareconnectsave.connection;

import com.shareconnectsave.connection.cache.IRequestLimitCache;
import com.shareconnectsave.connection.domain.ConnectionCreatedResponse;
import com.shareconnectsave.connection.domain.ConnectionRequest;
import com.shareconnectsave.connection.domain.ConnectionResponse;
import com.shareconnectsave.connection.domain.ConnectionStatus;
import com.shareconnectsave.connection.domain.CreateConnectionDto;
import com.shareconnectsave.connection.exception.ActiveConnectionExistsException;
import com.shareconnectsave.connection.exception.ConnectionNotFoundException;
import com.shareconnectsave.connection.exception.ForbiddenConnectionActionException;
import com.shareconnectsave.connection.exception.InvalidStateTransitionException;
import com.shareconnectsave.connection.exception.RequestLimitExceededException;
import com.shareconnectsave.connection.exception.SelfConnectionRequestException;
import com.shareconnectsave.connection.kafka.event.ConnectionAcceptedEvent;
import com.shareconnectsave.connection.kafka.event.ConnectionExpiredEvent;
import com.shareconnectsave.connection.kafka.event.ConnectionRequestedEvent;
import com.shareconnectsave.connection.mapper.ConnectionMapper;
import com.shareconnectsave.connection.outbox.IOutboxService;
import com.shareconnectsave.connection.saga.SagaStateRepository;
import com.shareconnectsave.connection.saga.SagaState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Single Responsibility (SOLID-S): every state-transition rule, limit
// check, and exception decision for the connection-request lifecycle lives
// HERE — ConnectionController only translates HTTP <-> DTOs and never
// contains an if/else of its own.
//
// Dependency Inversion (SOLID-D): every collaborator below is an interface
// (ConnectionRequestRepository, IRequestLimitCache, IOutboxService,
// SagaStateRepository, ConnectionMapper's generated bean) — none of them
// care whether the limit cache is in-memory today or Redis-backed later,
// and a unit test of this class can substitute any of the five without
// touching a single line of production wiring.
@Service
@RequiredArgsConstructor
public class ConnectionServiceImpl implements ConnectionService {

    private static final int PENDING_REQUEST_EXPIRY_MINUTES = 10;
    private static final String SAGA_TYPE_CONNECTION_LIFECYCLE = "ConnectionLifecycle";
    private static final String SAGA_STATUS_IN_PROGRESS = "in_progress";
    private static final String SAGA_STEP_ACCEPTED = "ACCEPTED";

    // Open/Closed (SOLID-O): the state machine is DATA, not a chain of
    // if/else branches inside accept()/decline(). Loosening or tightening
    // which transitions are legal later — e.g. adding a user-triggered
    // CANCELLED path — is a change to this map's contents, never a new
    // conditional branch in the guard-clause code below.
    private static final Map<ConnectionStatus, Set<ConnectionStatus>> VALID_TRANSITIONS = Map.of(
            ConnectionStatus.PENDING, Set.of(ConnectionStatus.ACCEPTED, ConnectionStatus.DECLINED)
    );

    private final ConnectionRequestRepository connectionRequestRepository;
    private final IRequestLimitCache requestLimitCache;
    private final IOutboxService outboxService;
    private final SagaStateRepository sagaStateRepository;
    private final ConnectionMapper connectionMapper;

    // @Transactional: the pending-count read, the active-connection read,
    // and the final INSERT must be seen by the DB as one unit of work — not
    // three separate auto-committed round trips — otherwise a concurrent
    // request from the same user could slip between "read" and "write" and
    // bypass both the request-limit and one-active-connection rules. The
    // filtered unique index from V001 is the last line of defense for the
    // duplicate-PENDING race specifically (see
    // ConnectionExceptionHandler.handleDataIntegrityViolation); this
    // annotation closes the wider window around every guard in this method.
    @Override
    @Transactional
    public ConnectionCreatedResponse createConnection(Long requesterId, CreateConnectionDto dto) {
        // Guard 0: no self-connections. Checked first and without touching
        // the DB at all — a user requesting themselves is never valid,
        // regardless of their current pending count or active-connection
        // state.
        if (requesterId.equals(dto.recipientId())) {
            throw new SelfConnectionRequestException(requesterId);
        }

        // Guard 1: request-limit throttle. Event-Driven Architecture — the
        // limit itself is never fetched with a synchronous call to
        // Rating/Trust Service; IRequestLimitCache holds a value T033's
        // trust.score.updated consumer will (eventually) keep current. A
        // possibly-stale local read is the deliberate trade-off event-driven
        // systems make in exchange for never blocking this request on
        // another service's availability.
        long pendingOutboundCount = connectionRequestRepository
                .countByRequesterIdAndStatus(requesterId, ConnectionStatus.PENDING);
        int limit = requestLimitCache.getLimit(requesterId);
        if (pendingOutboundCount >= limit) {
            throw new RequestLimitExceededException(requesterId, limit);
        }

        // Guard 2: one active connection at a time. Checked before insert
        // so a requester already occupied by an ACCEPTED connection never
        // accumulates a second PENDING request pointed at someone else.
        if (connectionRequestRepository.findByStatusAndEitherParty(ConnectionStatus.ACCEPTED, requesterId).isPresent()) {
            throw new ActiveConnectionExistsException(requesterId);
        }

        Instant now = Instant.now();
        ConnectionRequest connectionRequest = ConnectionRequest.builder()
                .requesterId(requesterId)
                .recipientId(dto.recipientId())
                .status(ConnectionStatus.PENDING)
                .expiresAt(now.plus(Duration.ofMinutes(PENDING_REQUEST_EXPIRY_MINUTES)))
                .build();
        // createdAt/updatedAt intentionally left null on this builder — both
        // columns are insertable = false on ConnectionRequest, so SQL
        // Server's own SYSUTCDATETIME() default fills them in at INSERT
        // time regardless of what this object holds in memory.

        ConnectionRequest saved = connectionRequestRepository.save(connectionRequest);

        // Outbox Pattern: the INSERT above and this outbox row commit
        // together inside this method's @Transactional boundary — Chat
        // Service has no reason to react to a brand-new PENDING request
        // (nothing to open yet), so only Notification Service subscribes to
        // connection.requested; unlike accept(), there is no saga_state
        // write here, since sending a request isn't a step of
        // ConnectionLifecycleSaga (that saga only starts once a request is
        // ACCEPTED).
        ConnectionRequestedEvent event = new ConnectionRequestedEvent(
                UUID.randomUUID().toString(),
                saved.getId(),
                requesterId,
                dto.recipientId(),
                now
        );
        // Partition key = connection_id (this event's aggregate id), per the
        // kafka-outbox skill's ordering rule - every producer keys by its
        // own aggregate id, never leaves the key null, so Kafka's
        // per-partition ordering guarantee actually protects this
        // connection's own event sequence.
        outboxService.publish("connection.requested", saved.getId().toString(), event.eventId(), event);

        return new ConnectionCreatedResponse(saved.getId());
    }

    // Pattern: Saga (Choreography), Step 1 of ConnectionLifecycleSaga.
    // Pattern: Outbox — the status update, the outbox row for
    // connection.accepted, and the saga_state upsert all happen inside this
    // ONE @Transactional method, in that exact order, so a crash between
    // any two of them can never leave a half-finished saga step: either all
    // three commit together, or none do.
    @Override
    @Transactional
    public ConnectionResponse acceptConnection(Long connectionId, Long callerId) {
        ConnectionRequest connectionRequest = requireConnection(connectionId);

        // Guard 1: ownership, checked FIRST. A caller who isn't even the
        // recipient has no business learning this connection's current
        // status or whether it's already been acted on — authorization
        // always outranks state/business-rule checks.
        requireRecipient(connectionRequest, callerId);

        // Guard 2: state validity.
        assertValidTransition(connectionRequest.getStatus(), ConnectionStatus.ACCEPTED);

        // Guard 3: neither party may already be in an ACCEPTED connection
        // elsewhere. accept() is the one place BOTH users' "one active
        // connection" rule must be enforced simultaneously — createConnection
        // only ever needed to check the requester.
        Long requesterId = connectionRequest.getRequesterId();
        Long recipientId = connectionRequest.getRecipientId();
        boolean requesterAlreadyActive = connectionRequestRepository
                .findByStatusAndEitherParty(ConnectionStatus.ACCEPTED, requesterId).isPresent();
        boolean recipientAlreadyActive = connectionRequestRepository
                .findByStatusAndEitherParty(ConnectionStatus.ACCEPTED, recipientId).isPresent();
        if (requesterAlreadyActive || recipientAlreadyActive) {
            throw new ActiveConnectionExistsException(requesterId, recipientId);
        }

        Instant acceptedAt = Instant.now();

        // Step 1 of 3: the local transaction. transitionTo (Tell, Don't
        // Ask on the entity) updates both status and updated_at together.
        connectionRequest.transitionTo(ConnectionStatus.ACCEPTED);
        ConnectionRequest saved = connectionRequestRepository.save(connectionRequest);

        // Step 2 of 3: write the event to the outbox, in the SAME
        // transaction. Connection Service has no idea Chat Service or
        // Notification Service exist, or what either will do with this
        // event — choreography, not orchestration.
        ConnectionAcceptedEvent event = new ConnectionAcceptedEvent(
                UUID.randomUUID().toString(),
                saved.getId(),
                requesterId,
                recipientId,
                acceptedAt
        );
        // Partition key = connection_id - see createConnection's
        // connection.requested call above for why.
        outboxService.publish("connection.accepted", saved.getId().toString(), event.eventId(), event);

        // Step 3 of 3: record this service's own saga_state row. sagaId ==
        // connectionId (the saga's natural key, per saga.md) — see
        // SagaState's class comment for why a plain save() here is already
        // an upsert.
        SagaState sagaState = SagaState.builder()
                .sagaId(saved.getId())
                .sagaType(SAGA_TYPE_CONNECTION_LIFECYCLE)
                .currentStep(SAGA_STEP_ACCEPTED)
                .status(SAGA_STATUS_IN_PROGRESS)
                .startedAt(acceptedAt)
                .updatedAt(acceptedAt)
                .build();
        sagaStateRepository.save(sagaState);

        return connectionMapper.toResponse(saved);
    }

    // @Transactional for consistency with acceptConnection's discipline:
    // decline has no outbox/saga write today, but the read-then-write here
    // is still not atomic without it, and keeping the same annotation
    // convention on every state-transition method means a future saga step
    // added to decline (e.g. a connection.declined topic) doesn't also
    // require remembering to retrofit this.
    @Override
    @Transactional
    public ConnectionResponse declineConnection(Long connectionId, Long callerId) {
        ConnectionRequest connectionRequest = requireConnection(connectionId);

        // Same guard order as acceptConnection: ownership before state.
        requireRecipient(connectionRequest, callerId);
        assertValidTransition(connectionRequest.getStatus(), ConnectionStatus.DECLINED);

        // No outbox/saga write here: decline is a terminal, single-service
        // outcome with no downstream participant reacting to it (no
        // connection.declined topic exists in this platform), so there is
        // no saga step to record.
        connectionRequest.transitionTo(ConnectionStatus.DECLINED);
        ConnectionRequest saved = connectionRequestRepository.save(connectionRequest);

        return connectionMapper.toResponse(saved);
    }

    @Override
    public List<ConnectionResponse> getPendingInbound(Long userId) {
        return connectionRequestRepository.findByRecipientIdAndStatus(userId, ConnectionStatus.PENDING)
                .stream()
                .map(connectionMapper::toResponse)
                .toList();
    }

    @Override
    public Optional<ConnectionResponse> getActiveConnection(Long userId) {
        return connectionRequestRepository.findByStatusAndEitherParty(ConnectionStatus.ACCEPTED, userId)
                .map(connectionMapper::toResponse);
    }

    // Read-only, no @Transactional needed here: Spring Data's own
    // SimpleJpaRepository already wraps every derived-query method (like
    // findByStatusAndExpiresAtBefore) in its own short, read-only
    // transaction — there is nothing else in this method for a wider
    // transaction boundary to protect. Mapping straight to ids, not
    // ConnectionRequest entities, also sidesteps a subtler trap: an entity
    // read here would be detached the moment this method returns (no
    // transaction/session left open around it), so
    // expireConnectionRequest re-reads each row fresh inside its own
    // transaction rather than being handed a stale, detached instance.
    @Override
    public List<Long> findOverdueConnectionRequestIds() {
        return connectionRequestRepository
                .findByStatusAndExpiresAtBefore(ConnectionStatus.PENDING, Instant.now())
                .stream()
                .map(ConnectionRequest::getId)
                .toList();
    }

    // Pattern: Saga (Choreography), compensating step of
    // ConnectionLifecycleSaga's no-response branch.
    // Pattern: Outbox — the EXPIRED transition and the connection.expired
    // outbox row commit together inside this ONE @Transactional method,
    // the same discipline acceptConnection uses for connection.accepted.
    // This method is called by ConnectionExpiryScheduler through the
    // injected ConnectionService bean (see this interface method's own
    // comment for why that indirection — not a private helper on this
    // class — is what makes @Transactional actually apply per request).
    @Override
    @Transactional
    public void expireConnectionRequest(Long connectionId) {
        Optional<ConnectionRequest> maybeConnectionRequest = connectionRequestRepository.findById(connectionId);
        if (maybeConnectionRequest.isEmpty()) {
            return;
        }

        ConnectionRequest connectionRequest = maybeConnectionRequest.get();

        // Idempotency guard clause: the id list this method receives was
        // read in a SEPARATE, earlier transaction
        // (findOverdueConnectionRequestIds), so by the time this
        // transaction opens, the row may already have moved on — accepted
        // or declined by a user in between, or expired by a previous
        // scheduler tick that overlapped this one. Re-checking PENDING
        // here, rather than trusting the caller's list, is what makes
        // calling this method twice for the same id a safe no-op the
        // second time.
        if (connectionRequest.getStatus() != ConnectionStatus.PENDING) {
            return;
        }

        // Step 1 of 2: the local transition (Tell, Don't Ask — same as
        // acceptConnection/declineConnection).
        connectionRequest.transitionTo(ConnectionStatus.EXPIRED);
        ConnectionRequest saved = connectionRequestRepository.save(connectionRequest);

        // Step 2 of 2: the outbox row, in the SAME transaction as the
        // status update above. Connection Service has no idea Notification
        // Service exists — this only states the fact that a request timed
        // out with no response.
        ConnectionExpiredEvent event = new ConnectionExpiredEvent(
                UUID.randomUUID().toString(),
                saved.getId(),
                saved.getRequesterId()
        );
        // Partition key = connection_id - same ordering rationale as
        // connection.requested/connection.accepted above. Keeping this
        // connection's own requested -> accepted/expired events on one
        // partition is what makes their relative order trustworthy for any
        // consumer that cares (e.g. a saga participant reacting to whichever
        // terminal event arrives).
        outboxService.publish("connection.expired", saved.getId().toString(), event.eventId(), event);
    }

    private ConnectionRequest requireConnection(Long connectionId) {
        return connectionRequestRepository.findById(connectionId)
                .orElseThrow(() -> new ConnectionNotFoundException(connectionId));
    }

    private void requireRecipient(ConnectionRequest connectionRequest, Long callerId) {
        if (!connectionRequest.getRecipientId().equals(callerId)) {
            throw new ForbiddenConnectionActionException(connectionRequest.getId(), callerId);
        }
    }

    private void assertValidTransition(ConnectionStatus from, ConnectionStatus to) {
        Set<ConnectionStatus> allowedNextStatuses = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowedNextStatuses.contains(to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }
}
