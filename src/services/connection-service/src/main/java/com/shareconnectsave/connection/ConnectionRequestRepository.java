package com.shareconnectsave.connection;

import com.shareconnectsave.connection.domain.ConnectionRequest;
import com.shareconnectsave.connection.domain.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Pattern: Repository (GoF/DDD) — same shape as discovery-service's
// ScanSessionRepository / User Service's IUserRepository. ConnectionService
// asks for "pending inbound for this recipient" or "the active connection
// involving this user" by name; it never writes a JPQL/SQL query itself, and
// never needs to know these rows live in SQL Server rather than, say,
// an in-memory fake used by a future unit test.
public interface ConnectionRequestRepository extends JpaRepository<ConnectionRequest, Long> {

    // GET /connections/pending: inbound requests only — a user's own
    // outbound PENDING requests are a different question (see
    // countByRequesterIdAndStatus below) with no endpoint of its own yet.
    List<ConnectionRequest> findByRecipientIdAndStatus(Long recipientId, ConnectionStatus status);

    // Backs the request-limit throttle in createConnection: "how many
    // PENDING requests has this requester already sent" is compared against
    // IRequestLimitCache.getLimit(requesterId).
    long countByRequesterIdAndStatus(Long requesterId, ConnectionStatus status);

    // "Does this user (either as requester OR recipient) already have an
    // ACCEPTED connection" — the query behind the platform-wide "one active
    // connection at a time" rule. Reused for three different callers:
    // createConnection's requester-side guard, acceptConnection's
    // both-parties guard, and GET /connections/active's read path. A single
    // @Query here, rather than three separate derived-query methods,
    // because Spring Data's method-name derivation has no clean way to
    // express "either column equals this value" — a hand-written JPQL
    // OR is the simpler option, not a workaround.
    @Query("SELECT c FROM ConnectionRequest c "
            + "WHERE c.status = :status AND (c.requesterId = :userId OR c.recipientId = :userId)")
    Optional<ConnectionRequest> findByStatusAndEitherParty(
            @Param("status") ConnectionStatus status,
            @Param("userId") Long userId);

    // Idempotency: this is the ONLY read ConnectionExpiryScheduler's tick
    // uses to decide what's overdue. Filtering strictly on
    // status = PENDING (a derived query, not a hand-written JPQL OR like
    // the method above — Spring Data's method-name derivation already
    // expresses "two ANDed equality/comparison conditions" cleanly) means a
    // row already moved to EXPIRED by a previous tick, or concurrently
    // ACCEPTED/DECLINED by a user in between ticks, is never picked up
    // again — re-running the same query on the same data twice is safe.
    List<ConnectionRequest> findByStatusAndExpiresAtBefore(ConnectionStatus status, Instant expiresAt);

    // T104 — backs PUT /admin/connections/users/{userId}/expire-active
    // (admin-suspend's immediate cleanup, as opposed to T031's TTL sweep).
    // Same hand-written JPQL OR as findByStatusAndEitherParty above, for the
    // same reason: Spring Data's method-name derivation has no clean way to
    // express "either column equals this value". Extended with
    // "status IN :statuses" because a suspended user's lingering connections
    // can be sitting in PENDING OR ACCEPTED — unlike the TTL job, which only
    // ever cares about PENDING (an ACCEPTED connection isn't "overdue", it's
    // mid-conversation, and only an admin action — not a timer — has the
    // authority to tear it down).
    @Query("SELECT c FROM ConnectionRequest c "
            + "WHERE (c.requesterId = :userId OR c.recipientId = :userId) "
            + "AND c.status IN :statuses")
    List<ConnectionRequest> findByEitherPartyAndStatusIn(
            @Param("userId") Long userId,
            @Param("statuses") List<ConnectionStatus> statuses);

    // T107 — backs GET /admin/connections/stats. A derived query, not a
    // hand-written JPQL OR like findByStatusAndEitherParty/
    // findByEitherPartyAndStatusIn above — "status equals X AND updatedAt
    // falls in [start, end)" is a plain ANDed comparison, exactly what
    // Spring Data's method-name derivation already expresses cleanly, so a
    // @Query here would just be more ceremony for no gain. GreaterThanEqual
    // + LessThan (NOT a single "Between"): SQL's BETWEEN is inclusive on
    // BOTH ends, which would let a row updated at exactly midnight of the
    // FOLLOWING UTC day double-count into today's total — the half-open
    // interval this expresses is the only boundary-safe way to say "this one
    // UTC calendar day". Takes the boundary Instants as parameters rather
    // than computing "today" itself, per this ticket's own rule: the
    // repository must stay testable with any fixed instant, never reach for
    // the system clock on its own.
    long countByStatusAndUpdatedAtGreaterThanEqualAndUpdatedAtLessThan(
            ConnectionStatus status, Instant startInclusiveUtc, Instant endExclusiveUtc);
}
