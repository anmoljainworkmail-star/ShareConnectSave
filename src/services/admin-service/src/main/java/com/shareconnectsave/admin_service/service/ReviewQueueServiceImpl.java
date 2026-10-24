package com.shareconnectsave.admin_service.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.shareconnectsave.admin_service.client.ConnectionServiceClient;
import com.shareconnectsave.admin_service.client.RatingServiceClient;
import com.shareconnectsave.admin_service.client.RatingServiceClient.TrustScoreSnapshot;
import com.shareconnectsave.admin_service.client.ReportServiceClient;
import com.shareconnectsave.admin_service.client.ReportServiceClient.ReportHistoryItem;
import com.shareconnectsave.admin_service.client.UpstreamUnavailableException;
import com.shareconnectsave.admin_service.client.UserServiceClient;
import com.shareconnectsave.admin_service.client.UserServiceClient.SuspendedUserSummary;
import com.shareconnectsave.admin_service.client.UserServiceClient.UserProfileSummary;
import com.shareconnectsave.admin_service.dto.ReinstateResponse;
import com.shareconnectsave.admin_service.dto.ReportHistoryEntry;
import com.shareconnectsave.admin_service.dto.ReviewQueueItem;
import com.shareconnectsave.admin_service.dto.SuspendResponse;
import com.shareconnectsave.admin_service.dto.SuspendUserRequest;
import com.shareconnectsave.admin_service.dto.UserDetailResponse;
import com.shareconnectsave.admin_service.exception.ConnectionServiceUnavailableException;
import com.shareconnectsave.admin_service.exception.UpstreamUnavailableForWriteException;
import com.shareconnectsave.admin_service.exception.UserNotFoundException;
import com.shareconnectsave.admin_service.kafka.event.TrustScoreUpdatedEvent;
import com.shareconnectsave.admin_service.mapper.ReportHistoryEntryMapper;
import com.shareconnectsave.admin_service.outbox.IOutboxService;

import lombok.RequiredArgsConstructor;

// Facade-like aggregation (per this ticket's own "Patterns demonstrated"
// section), plain English: a facade is one simple front door in front of
// several moving parts behind it — think of a hotel concierge desk: you ask
// the concierge for "a dinner reservation and a taxi", and the concierge
// calls the restaurant AND the taxi company for you, instead of you having
// to know either phone number yourself. ReviewQueueController and the future
// admin UI (T071) only ever ask THIS class for "the queue" or "this user's
// detail view" — they never call UserServiceClient/RatingServiceClient/
// ReportServiceClient themselves, and never need to learn a second thing
// changed if a fourth upstream service joins this aggregation later.
//
// Dependency Inversion (SOLID-D): every collaborator below is an interface
// (UserServiceClient, RatingServiceClient, ReportServiceClient,
// IOutboxService) — this class can be exercised with in-memory fakes in a
// future unit test without a single real WebClient/Kafka call.
@Service
@RequiredArgsConstructor
public class ReviewQueueServiceImpl implements ReviewQueueService {

    private static final String TOPIC_TRUST_SCORE_UPDATED = "trust.score.updated";
    private static final String STATUS_SUSPENDED = "suspended";
    private static final String STATUS_ACTIVE = "active";

    // Tech Lead decision (2026-10-24): mirrors Rating Service's own
    // RequestLimit.THROTTLED.wireValue() (rating-service's RequestLimit.java)
    // — duplicated here, not imported, because Database/Domain per Service
    // means admin-service never depends on another service's domain classes;
    // this is the wire INTEGER the two services already agree on at the
    // trust.score.updated contract boundary, same category of duplication as
    // this class's own STATUS_ACTIVE/STATUS_SUSPENDED strings mirroring User
    // Service's wire values. See reinstate()'s own comment for why this
    // floor exists.
    private static final int REINSTATED_REQUEST_LIMIT = 3;

    // T059: the OPPOSITE floor from REINSTATED_REQUEST_LIMIT above — a
    // suspend always drives request_limit all the way to Rating Service's
    // own SUSPENDED wire value (0), never merely lowers it, so Discovery's
    // TrustScoreUpdatedEventListener (T025) removes this user from
    // scan:eligible_users unconditionally. Unlike REINSTATED_REQUEST_LIMIT,
    // this is not a Math.max floor over the user's real persisted limit —
    // suspend always WINS over whatever Rating Service currently has on
    // file, same as how a suspended account overrides any in-flight
    // automated trust recalculation until a future reinstate() reverses it.
    private static final int SUSPENDED_REQUEST_LIMIT = 0;

    // T059: the lowest of Rating Service's own three-way trusted/standard/
    // restricted badge tiering (T043's TrustScoreRecalculationServiceImpl) —
    // reused verbatim, never a fourth tier invented here (see this ticket's
    // own "What NOT to do"). Open/Closed (SOLID-O): if Rating Service's
    // tiering ever grows a new name for this tier, this ONE constant is the
    // only place admin-service needs to change to stay in sync.
    private static final String BADGE_LEVEL_RESTRICTED = "restricted";

    private final UserServiceClient userServiceClient;
    private final RatingServiceClient ratingServiceClient;
    private final ReportServiceClient reportServiceClient;
    private final ConnectionServiceClient connectionServiceClient;
    private final IOutboxService outboxService;
    private final ReportHistoryEntryMapper reportHistoryEntryMapper;

    // GET /admin/queue. getSuspendedUsers() itself has NO fallback (see
    // UserServiceClientImpl's class comment) — if User Service cannot be
    // reached, there is no queue to approximate, and this method is left to
    // throw out to GlobalExceptionHandler as a 500. The PER-ITEM enrichment
    // below (trust score, report count) is what actually exercises this
    // ticket's "Partial-failure degradation" spec, one suspended user's row
    // at a time, same as getUserDetail below.
    @Override
    public List<ReviewQueueItem> getQueue() {
        List<SuspendedUserSummary> suspendedUsers = userServiceClient.getSuspendedUsers();

        return suspendedUsers.stream()
                .map(this::toReviewQueueItem)
                .toList();
    }

    private ReviewQueueItem toReviewQueueItem(SuspendedUserSummary suspendedUser) {
        Double trustScore = null;
        boolean trustScoreUnavailable = false;
        try {
            trustScore = ratingServiceClient.getTrustScore(suspendedUser.userId()).trustScore();
        } catch (UpstreamUnavailableException e) {
            trustScoreUnavailable = true;
        }

        Integer reportCount = null;
        boolean reportCountUnavailable = false;
        try {
            reportCount = reportServiceClient.getReportHistory(suspendedUser.userId()).size();
        } catch (UpstreamUnavailableException e) {
            reportCountUnavailable = true;
        }

        return new ReviewQueueItem(
                suspendedUser.userId(),
                suspendedUser.displayName(),
                STATUS_SUSPENDED,
                trustScore,
                trustScoreUnavailable,
                reportCount,
                reportCountUnavailable,
                suspendedUser.suspendedAt());
    }

    // GET /admin/users/{id}. Exactly three upstream calls, aggregated — see
    // this class's own header comment. Each call is wrapped in its OWN
    // try/catch for UpstreamUnavailableException (never one try/catch around
    // all three) specifically so ONE failing slice can never short-circuit
    // the other two — that independence IS the ticket's "Partial-failure
    // degradation" requirement, not an incidental side effect of the code
    // shape.
    //
    // UserNotFoundException is NOT caught here — it is a different exception
    // type from UpstreamUnavailableException on purpose (see that class's own
    // comment): a genuine 404 must still 404, never silently degrade into an
    // "unavailable" placeholder as if User Service were merely down.
    @Override
    public UserDetailResponse getUserDetail(String userId) {
        String displayName = null;
        String status = null;
        boolean profileUnavailable = false;
        try {
            UserProfileSummary profile = userServiceClient.getUser(userId);
            if (profile == null) {
                throw new UserNotFoundException(userId);
            }
            displayName = profile.displayName();
            status = profile.accountStatus();
        } catch (UpstreamUnavailableException e) {
            profileUnavailable = true;
        }

        Double trustScore = null;
        boolean trustScoreUnavailable = false;
        try {
            trustScore = ratingServiceClient.getTrustScore(userId).trustScore();
        } catch (UpstreamUnavailableException e) {
            trustScoreUnavailable = true;
        }

        List<ReportHistoryEntry> reportHistory = List.of();
        boolean reportHistoryUnavailable = false;
        try {
            List<ReportHistoryItem> history = reportServiceClient.getReportHistory(userId);
            reportHistory = history.stream()
                    .map(reportHistoryEntryMapper::toReportHistoryEntry)
                    .toList();
        } catch (UpstreamUnavailableException e) {
            reportHistoryUnavailable = true;
        }

        return new UserDetailResponse(
                userId,
                displayName,
                status,
                profileUnavailable,
                trustScore,
                trustScoreUnavailable,
                reportHistory,
                reportHistory.size(),
                reportHistoryUnavailable);
    }

    // POST /admin/users/{id}/reinstate. Two steps, in this exact order, per
    // the ticket's own "mechanics" spec:
    //   1. UserServiceClient.setStatus — synchronous, no fallback. If this
    //      throws, we never reach step 2 at all, and the whole request fails
    //      (propagates to GlobalExceptionHandler) with User Service
    //      untouched — the cleanest possible failure, nothing to reconcile.
    //   2. Fetch the user's CURRENT trust score and write ONE outbox
    //      document (the entire local "transaction" this endpoint has — see
    //      AdminOutboxServiceImpl's own comment for why no multi-write
    //      transaction is needed here).
    // If step 1 succeeds but step 2 cannot (Rating Service unavailable), this
    // throws UpstreamUnavailableForWriteException rather than publishing a
    // fabricated trust score — see that exception's own comment for why
    // retrying the whole request afterward is always safe (setStatus is
    // idempotent), which is this ticket's own definition of "the whole
    // reinstate request should fail (rollback)" given admin-service has no
    // local database row of its own to literally roll back.
    @Override
    public ReinstateResponse reinstate(String userId) {
        UserProfileSummary updated = userServiceClient.setStatus(userId, STATUS_ACTIVE);

        TrustScoreSnapshot snapshot;
        try {
            snapshot = ratingServiceClient.getTrustScore(userId);
        } catch (UpstreamUnavailableException e) {
            throw new UpstreamUnavailableForWriteException(userId, e);
        }

        // "Reinstated limits" (per SPECS.md's own wording), RESOLVED (Tech
        // Lead decision, 2026-10-24): admin-service still does not
        // recalculate the trust SCORE itself — TrustScoreCalculator in
        // Rating Service remains the one and only place that number is
        // computed (Single Responsibility, SOLID-S; CLAUDE.md's own SOLID
        // table makes this exact point). But a bare echo of Rating Service's
        // current request_limit would make reinstate a no-op whenever that
        // number is still SUSPENDED (0) — the admin's "give this account
        // another chance" action would silently fail to make the user
        // visible in Discovery again, immediately contradicting itself. So
        // this ONE field is floored at THROTTLED, never lower: a deliberate
        // probationary limit, not the real computed number, and not a
        // promotion to UNLIMITED either — "another chance to be visible,
        // capped" is the ticket's own intent, not "wipe the slate clean."
        // Math.max relies on the three wire values already being ordered by
        // severity (SUSPENDED 0 < THROTTLED 3 < UNLIMITED Integer.MAX_VALUE,
        // see rating-service's RequestLimit.wireValue()), so a user whose
        // real score already computes to THROTTLED or UNLIMITED is left
        // completely untouched — this only ever raises a SUSPENDED floor, it
        // never downgrades a genuinely-better number. This override is local
        // to THIS one published event; it writes nothing back to Rating
        // Service's own trust_score/request_limit columns, so the very next
        // rating.submitted recalculation there overwrites it with the real,
        // current number again, same as any other trust.score.updated
        // publish. Flagged decision point, still open: the badgeLevel
        // mapping below is a SIMPLIFIED two-way (trusted/standard)
        // derivation from TrustScoreSnapshot.trusted(), not Rating Service's
        // own three-way trusted/standard/restricted tiering — see
        // TrustScoreUpdatedEvent's own class comment.
        int requestLimit = Math.max(snapshot.requestLimit(), REINSTATED_REQUEST_LIMIT);

        String eventId = UUID.randomUUID().toString();
        TrustScoreUpdatedEvent event = new TrustScoreUpdatedEvent(
                eventId,
                userId,
                snapshot.trustScore(),
                snapshot.trusted() ? "trusted" : "standard",
                requestLimit);

        outboxService.publish(TOPIC_TRUST_SCORE_UPDATED, userId, eventId, event);

        // updated.accountStatus() (not the literal STATUS_ACTIVE constant):
        // UserServiceClientImpl.setStatus never returns null (it throws
        // IllegalStateException on a missing body instead — see that
        // method's own comment), so this always reflects User Service's own
        // confirmed value rather than this service assuming the write did
        // what it asked for.
        return new ReinstateResponse(userId, updated.accountStatus(), Instant.now());
    }

    // POST /admin/users/{id}/suspend. Simple synchronous orchestration (per
    // this ticket's own "Patterns demonstrated" — explicitly NOT a Saga: no
    // compensating-transaction step exists if a later leg fails, only a
    // documented "stop and let the caller retry" contract), three steps in
    // this exact order, mirroring reinstate()'s own structure in reverse:
    //   1. UserServiceClient.setStatus — synchronous, no fallback. If this
    //      throws, nothing below runs and User Service is left untouched,
    //      same "cleanest possible failure, nothing to reconcile" reasoning
    //      as reinstate()'s own step 1.
    //   2. ConnectionServiceClient.expireActiveConnections — synchronous, no
    //      fallback VALUE (ConnectionServiceClientImpl's circuit breaker
    //      fallback re-throws, never degrades). If this fails, User Service
    //      has ALREADY been flipped to suspended — this method still must
    //      not publish a trust.score.updated event for a half-finished
    //      suspend (same "don't leave an inconsistent state as if it
    //      succeeded" rule reinstate()'s own Rating Service step follows),
    //      so the failure is wrapped into ConnectionServiceUnavailableException
    //      and the whole request fails — see that exception's own comment
    //      for why retrying afterward is always safe.
    //   3. Fetch the user's CURRENT trust score (same call reinstate() makes
    //      — Single Responsibility, SOLID-S: admin-service never recomputes
    //      a score, only republishes the one Rating Service already has on
    //      file) and write ONE outbox document with request_limit forced to
    //      SUSPENDED_REQUEST_LIMIT and badge_level forced to
    //      BADGE_LEVEL_RESTRICTED — the entire local "transaction" this
    //      endpoint has (see AdminOutboxServiceImpl's own comment).
    @Override
    public SuspendResponse suspend(String userId, SuspendUserRequest request) {
        UserProfileSummary updated = userServiceClient.setStatus(userId, STATUS_SUSPENDED);

        int expiredConnectionCount;
        try {
            expiredConnectionCount = connectionServiceClient.expireActiveConnections(userId);
        } catch (UpstreamUnavailableException e) {
            throw new ConnectionServiceUnavailableException(userId, e);
        }

        TrustScoreSnapshot snapshot;
        try {
            snapshot = ratingServiceClient.getTrustScore(userId);
        } catch (UpstreamUnavailableException e) {
            throw new UpstreamUnavailableForWriteException(userId, e);
        }

        // new_score is deliberately snapshot.trustScore() UNCHANGED — this
        // ticket's own "What NOT to do" is explicit that a suspend is an
        // AVAILABILITY action, not a re-scoring action. Only request_limit
        // and badge_level are overridden below; TrustScoreCalculator in
        // Rating Service remains the one and only place a score is computed
        // (the very next rating.submitted recalculation there republishes
        // the real number again, same as reinstate()'s own override is only
        // ever local to the one event it publishes).
        String eventId = UUID.randomUUID().toString();
        TrustScoreUpdatedEvent event = new TrustScoreUpdatedEvent(
                eventId,
                userId,
                snapshot.trustScore(),
                BADGE_LEVEL_RESTRICTED,
                SUSPENDED_REQUEST_LIMIT);

        outboxService.publish(TOPIC_TRUST_SCORE_UPDATED, userId, eventId, event);

        return new SuspendResponse(userId, updated.accountStatus(), expiredConnectionCount, Instant.now());
    }
}
