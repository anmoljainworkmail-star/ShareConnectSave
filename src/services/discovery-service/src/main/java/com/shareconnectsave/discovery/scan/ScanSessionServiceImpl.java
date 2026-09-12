package com.shareconnectsave.discovery.scan;

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
    public ScanStartResponse startScan(Long userId, ScanStartRequest request) {
        ScanSession session = ScanSession.builder()
                .userId(userId)
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .destinationLabel(request.destinationLabel())
                .departureTime(request.departureTime())
                .build();

        ScanSession saved = scanSessionRepository.save(session);
        return new ScanStartResponse(saved.getId(), "gps");
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

    private ScanSession requireActiveSession(Long userId) {
        return scanSessionRepository.findFirstByUserIdAndEndedAtIsNullOrderByIdDesc(userId)
                .orElseThrow(() -> new ScanSessionNotFoundException(userId));
    }
}
