package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.cache.DiscoveryCacheService;
import com.shareconnectsave.discovery.scan.domain.ScanLocation;
import com.shareconnectsave.discovery.scan.domain.ScanLocationRequest;
import com.shareconnectsave.discovery.scan.domain.ScanSession;
import com.shareconnectsave.discovery.scan.domain.ScanStartRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Single Responsibility (SOLID-S) — this class owns exactly the
// scan lifecycle (start/stop) and the Redis-only live-location bookkeeping
// underneath it. It does not compute nearby matches, call User Service, or
// apply trust-score/gender filters — that is T023's job, injected here as a
// separate collaborator once it exists rather than folded into this class.
//
// T026 note: session location, scan:active_sessions membership, and the
// eligibility gate are now read/written through DiscoveryCacheService (see
// that class for the TTL/key mechanics). gender/women_only below deliberately
// stay on the raw RedisTemplate this class already held — they are per-session
// identity attributes with NO TTL of their own (set once at /scan/start,
// deleted once at /scan/stop), not a TTL'd cache or a managed membership Set,
// so they fall outside T026's own key table and DiscoveryCacheService's
// documented scope. Consolidating a non-cache value into a class named
// DiscoveryCacheService would blur "cache" with "session state that happens
// to live in Redis" — the same line T023's ScanCacheService already drew.
//
// Only one @Service implementation of ScanSessionService exists today, so
// there is nothing to switch between yet — no external paid/rate-limited
// dependency here the way Twilio's SMS API is for User Service, so no stub
// is added speculatively. The interface exists so that WHEN one is needed
// (e.g. a fake/in-memory ScanSessionService for a fast unit test of
// ScanController, or a future backing-store swap), the pattern to reach for
// is: implement ScanSessionService again, mark the new bean
// @Profile("dev")/@ConditionalOnProperty the same way StubTwilioClient is
// gated by TWILIO_STUB in Program.cs, and ScanController's dependency on
// the interface never has to change.
@Service
@RequiredArgsConstructor
public class ScanSessionServiceImpl implements ScanSessionService {

    private final ScanSessionRepository scanSessionRepository;

    // gender/women_only only, as of T026 — see class comment.
    private final RedisTemplate<String, Object> redisTemplate;

    private final DiscoveryCacheService discoveryCacheService;

    @Override
    public ScanStartResponse startScan(Long userId, String gender, ScanStartRequest request) {
        // Guard clause (fail fast): scan:eligible_users is populated only by
        // the user.verified Kafka consumer, and emptied by trust.score.updated
        // on suspension (see DiscoveryCacheService). findNearby
        // already filters candidates through this same gate, but that only
        // ever protected the OTHER side of a match — nothing stopped an
        // unverified or suspended caller from opening their own session and
        // still seeing everyone else. Checking here, before a ScanSession row
        // or any active_sessions membership is created, closes that gap at
        // the one place both problems share: neither side effect should ever
        // happen for an ineligible caller.
        if (!discoveryCacheService.isEligible(String.valueOf(userId))) {
            throw new UserNotEligibleForDiscoveryException(userId);
        }

        ScanSession session = ScanSession.builder()
                .userId(userId)
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .destinationLabel(request.destinationLabel())
                .departureTime(request.departureTime())
                .build();

        ScanSession saved = scanSessionRepository.save(session);
        Long sessionId = saved.getId();

        // T023 addition: nothing before this ticket ever populated
        // scan:active_sessions, but ScanQueryServiceImpl's whole nearby-query
        // depends on enumerating it. A session only counts as "looking" (the
        // ticket's status predicate) while it is a MEMBER of this Set — added
        // here, removed in stopScan below — so query-time code never has to
        // scan every scan_sessions row hunting for ended_at IS NULL.
        discoveryCacheService.addActiveSession(sessionId);

        // Women-only mode is resolved ONCE, here, and stored as the
        // already-decided boolean — never re-derived at query time from a
        // client-supplied flag. The trusted-header rule (X-User-Gender is
        // gateway-injected, never client-asserted JSON) means the ONLY
        // correct place to decide "does this session's own women-only
        // request actually apply" is right where that header is available:
        // request.womenOnly() alone is not enough, it must also require
        // gender == female, otherwise a non-female caller could set
        // women_only=true in the body and have it silently accepted.
        boolean womenOnlyRequested = Boolean.TRUE.equals(request.womenOnly());
        boolean effectiveWomenOnly = womenOnlyRequested && "female".equalsIgnoreCase(gender);

        // No TTL on either key below, unlike the location key: they must
        // live exactly as long as this session does, deleted deterministically
        // in stopScan (never left to expire independently mid-session) — same
        // "explicit delete, not just a timeout" reasoning this class already
        // applies to the location key.
        //
        // gender is never null here: the gateway's JwtValidationMiddleware
        // rejects any JWT missing the "gender" claim before this controller
        // is ever reached (see ScanController.startScan's header comment),
        // so there is no absent-value case to guard against — only the
        // literal value "Unspecified" for accounts that never set a gender.
        redisTemplate.opsForValue().set(genderKey(sessionId), gender);
        redisTemplate.opsForValue().set(womenOnlyKey(sessionId), effectiveWomenOnly);

        return new ScanStartResponse(sessionId, "gps");
    }

    // Pattern: Pessimistic locking (TOCTOU race prevention) — @Transactional
    // here is what keeps the PESSIMISTIC_WRITE row lock taken by
    // requireActiveSession() held for the *entire* method body, including the
    // Redis delete, releasing it only at commit. Without this, the lock (if
    // taken at all) would release the instant the finder returns, and a
    // concurrent updateLocation() could still slip a location write in
    // between this method's close() and its removeSessionLocation() call.
    @Override
    @Transactional
    public void stopScan(Long userId) {
        ScanSession session = requireActiveSession(userId);
        session.close();
        scanSessionRepository.save(session);

        // Deleted immediately, not left to expire on its own: the acceptance
        // criterion is "Redis key is gone right after /scan/stop", not
        // "gone within 30 seconds of /scan/stop" — the TTL is a safety net
        // for a client that never calls stop, not a substitute for calling
        // delete() here.
        discoveryCacheService.removeSessionLocation(session.getId());

        // T023 additions, same "explicit delete on stop" reasoning as the
        // location key above: a session that has stopped must disappear from
        // active_sessions immediately (not linger until some future TTL), and
        // its gender/women_only keys — which intentionally carry no TTL of
        // their own — must be reclaimed deterministically here or they would
        // never be cleaned up at all.
        discoveryCacheService.removeActiveSession(session.getId());
        redisTemplate.delete(genderKey(session.getId()));
        redisTemplate.delete(womenOnlyKey(session.getId()));
    }

    // See stopScan's note: @Transactional makes this method hold the same
    // row lock for its full duration, so it either completes its Redis write
    // entirely before a concurrent stopScan's delete, or entirely after —
    // never interleaved with it.
    @Override
    @Transactional
    public void updateLocation(Long userId, ScanLocationRequest request) {
        ScanSession session = requireActiveSession(userId);

        // This method must never touch scan_sessions or any other SQL table
        // (Location Privacy) — the write below is the only side effect, and
        // DiscoveryCacheService.cacheSessionLocation applies its TTL in the
        // same Redis call, never a plain SET followed by a separate EXPIRE
        // that a crash could land between.
        discoveryCacheService.cacheSessionLocation(session.getId(), new ScanLocation(request.lat(), request.lng()));
    }

    // Public + static, one place, callers reuse it — ScanQueryServiceImpl reads these two keys back at
    // query time without duplicating the "scan:{id}:gender" / "...women_only"
    // string shape in a second file.
    public static String genderKey(Long sessionId) {
        return "scan:%d:gender".formatted(sessionId);
    }

    public static String womenOnlyKey(Long sessionId) {
        return "scan:%d:women_only".formatted(sessionId);
    }

    private ScanSession requireActiveSession(Long userId) {
        return scanSessionRepository.findFirstByUserIdAndEndedAtIsNullOrderByIdDesc(userId)
                .orElseThrow(() -> new ScanSessionNotFoundException(userId));
    }
}
