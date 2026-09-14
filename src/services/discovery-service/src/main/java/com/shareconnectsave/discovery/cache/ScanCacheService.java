package com.shareconnectsave.discovery.cache;

import com.shareconnectsave.discovery.config.DiscoveryProperties;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

// Pattern: Cache-Aside — every method here is a thin get/set pair over
// Redis; the CALLER (ScanQueryServiceImpl) decides WHEN to check the cache
// and WHEN to populate it on a miss. This class only knows HOW: key shape
// and TTL, nothing else — no filtering logic, no HTTP calls.
//
// Scope note (T023 tech-lead research): this class intentionally owns ONLY
// the nearby-result cache, the block-list cache, and scan:active_sessions
// Set operations. It deliberately does NOT also take over
// ScanSessionServiceImpl's existing location-key RedisTemplate calls —
// consolidating every Redis access in this service behind one class is a
// real improvement, but it is explicitly T026's job (a separate,
// not-yet-approved ticket). Folding that in here would silently widen this
// ticket's diff into code no T023 acceptance criterion touches.
@Component
@RequiredArgsConstructor
public class ScanCacheService {

    // Single source of truth for this literal string: ScanSessionServiceImpl
    // (SADD on /scan/start, SREM on /scan/stop) and this class (SMEMBERS at
    // query time) both reference this constant instead of typing
    // "scan:active_sessions" out separately in two files where one copy
    // could silently drift from the other (e.g. a typo'd plural/singular).
    public static final String ACTIVE_SESSIONS_KEY = "scan:active_sessions";

    private final RedisTemplate<String, Object> redisTemplate;

    // Options pattern (T023) — see DiscoveryProperties. Converted to a
    // Duration inline at each call site below rather than precomputed once
    // in a constructor: with @RequiredArgsConstructor generating the
    // constructor, there is no longer a constructor body to do that
    // conversion in, and Duration.ofSeconds/ofMinutes is cheap enough that
    // computing it on every call costs nothing measurable.
    private final DiscoveryProperties discoveryProperties;

    public Set<Object> getActiveSessionIds() {
        Set<Object> members = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_KEY);
        return members != null ? members : Set.of();
    }

    // Returns null on a cache MISS (key absent/expired) so the caller can
    // tell "nothing cached yet" apart from "cached, and it happens to be an
    // empty list" (a real, valid result when nobody is nearby).
    @SuppressWarnings("unchecked")
    public List<UserCardResponse> getCachedNearbyResult(Long sessionId) {
        Object cached = redisTemplate.opsForValue().get(nearbyKey(sessionId));
        return cached instanceof List<?> ? (List<UserCardResponse>) cached : null;
    }

    // 10s TTL, matching the acceptance criterion exactly: a second query for
    // the same session inside this window is served from Redis, never
    // recomputed — the "10 seconds" the AC names is spelled out in exactly
    // one place (application.yml), never re-typed as a literal here.
    public void cacheNearbyResult(Long sessionId, List<UserCardResponse> results) {
        redisTemplate.opsForValue().set(
                nearbyKey(sessionId), results, Duration.ofSeconds(discoveryProperties.cache().nearbyResultTtlSeconds()));
    }

    @SuppressWarnings("unchecked")
    public List<Long> getCachedBlockList(Long userId) {
        Object cached = redisTemplate.opsForValue().get(blockListKey(userId));
        return cached instanceof List<?> ? (List<Long>) cached : null;
    }

    // Caching an EMPTY list is deliberate, not a bug: User Service's /blocks
    // endpoint does not exist yet (T023 known limitation — see
    // UserServiceClientImpl), so every live call currently fails and
    // degrades to List.of(). Caching that empty result for the same 5-minute
    // TTL a real block list would get is what keeps a persistently-missing
    // endpoint from being hammered on every single candidate, every query.
    public void cacheBlockList(Long userId, List<Long> blockedUserIds) {
        redisTemplate.opsForValue().set(
                blockListKey(userId), blockedUserIds, Duration.ofMinutes(discoveryProperties.cache().blocklistTtlMinutes()));
    }

    public static String nearbyKey(Long sessionId) {
        return "scan:%d:nearby".formatted(sessionId);
    }

    public static String blockListKey(Long userId) {
        return "user:blocklist:%d".formatted(userId);
    }
}
