package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.cache.ScanCacheService;
import com.shareconnectsave.discovery.scan.domain.ScanLocation;
import com.shareconnectsave.discovery.scan.domain.ScanLocationRequest;
import com.shareconnectsave.discovery.scan.domain.ScanSession;
import com.shareconnectsave.discovery.scan.domain.ScanStartRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

// Pattern: Single Responsibility (SOLID-S) — this class owns exactly the
// scan lifecycle (start/stop) and the Redis-only live-location bookkeeping
// underneath it. It does not compute nearby matches, call User Service, or
// apply trust-score/gender filters — that is T023's job, injected here as a
// separate collaborator once it exists rather than folded into this class.
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

    // Ephemeral storage as a privacy control: a 30s TTL on this Redis key is
    // what guarantees a raw GPS fix is never retained beyond a short window,
    // even if the client goes silent (killed app, lost network) right after
    // the last PUT /scan/location — Redis's own EXPIRE is what guarantees
    // deletion without any app code having to remember to run a cleanup job.
    private static final Duration LOCATION_TTL = Duration.ofSeconds(30);

    private final ScanSessionRepository scanSessionRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public ScanStartResponse startScan(Long userId, String gender, ScanStartRequest request) {
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
        redisTemplate.opsForSet().add(ScanCacheService.ACTIVE_SESSIONS_KEY, String.valueOf(sessionId));

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
    // between this method's close() and its redisTemplate.delete() call.
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
        redisTemplate.delete(locationKey(session.getId()));

        // T023 additions, same "explicit delete on stop" reasoning as the
        // location key above: a session that has stopped must disappear from
        // active_sessions immediately (not linger until some future TTL), and
        // its gender/women_only keys — which intentionally carry no TTL of
        // their own — must be reclaimed deterministically here or they would
        // never be cleaned up at all.
        redisTemplate.opsForSet().remove(ScanCacheService.ACTIVE_SESSIONS_KEY, String.valueOf(session.getId()));
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
        // (Location Privacy) — the write below is the only side effect,
        // and it goes to Redis with an explicit TTL in the same call, never
        // a plain SET followed by a separate EXPIRE that a crash could land
        // between.
        redisTemplate.opsForValue().set(
                locationKey(session.getId()),
                new ScanLocation(request.lat(), request.lng()),
                LOCATION_TTL
        );
    }

    // Public + static so T023's nearby-search query can build the identical
    // key shape to read back what this class writes, without duplicating the
    // "scan:{id}:location" format in a second place.
    public static String locationKey(Long sessionId) {
        return "scan:%d:location".formatted(sessionId);
    }

    // Same "public static, one place, callers reuse it" convention as
    // locationKey above — ScanQueryServiceImpl reads these two keys back at
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
