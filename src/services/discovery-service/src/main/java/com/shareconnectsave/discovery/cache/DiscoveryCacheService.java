package com.shareconnectsave.discovery.cache;

import com.shareconnectsave.discovery.client.UserServiceClient;
import com.shareconnectsave.discovery.config.DiscoveryProperties;
import com.shareconnectsave.discovery.scan.domain.ScanLocation;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Pattern: Single Responsibility (SOLID-S) + Cache-Aside (T026 consolidation)
// — every Redis interaction in this service now goes through this one class.
// Before this ticket, three separate call sites each owned their own slice of
// Redis: ScanCacheService (nearby-result cache, block-list cache,
// scan:active_sessions), DiscoveryEligibilityCacheService (scan:eligible_users,
// trust metadata) and UserServiceClientImpl (a fallback-only profile cache it
// wrote to directly). Splitting Redis access across three classes meant three
// places a TTL, a key format, or a serialization choice could silently drift
// from the other two. This class replaces all three: Controllers and Services
// depend on DiscoveryCacheService alone (Dependency Inversion, SOLID-D) and
// never see a RedisTemplate or reconstruct a key string themselves.
//
// The Cache-Aside orchestration for profile/blocklist lookups also moves here
// from where it used to live. Before T026, UserServiceClientImpl always made a
// LIVE WebClient call first and only consulted its cache when that call
// failed — a write-through-with-fallback shape, not true cache-aside (which
// checks the cache FIRST and only calls upstream on a genuine miss). getProfile
// and getBlocklist below fix that: check Redis, return on a hit, and only ask
// UserServiceClient (still Resilience4j-guarded — see that class) when nothing
// is cached. This is a real behavior change, not just a file move: a warm
// cache now serves every /scan/nearby query without a User Service round trip
// at all, not merely during an outage.
@Component
@Slf4j
@RequiredArgsConstructor
public class DiscoveryCacheService {

    // Public: referenced in a couple of diagnostic log messages elsewhere in
    // this service (e.g. "session is in scan:active_sessions but has no
    // active SQL row") — the key NAME is useful there, the key's internal
    // shape (how it's built from a session id) is not, so only the constant
    // is exposed, never a key-builder method.
    public static final String ACTIVE_SESSIONS_KEY = "scan:active_sessions";
    public static final String ELIGIBLE_USERS_KEY = "scan:eligible_users";

    private static final String TRUST_SCORE_FIELD = "new_score";
    private static final String BADGE_LEVEL_FIELD = "badge_level";
    private static final String REQUEST_LIMIT_FIELD = "request_limit";

    private final RedisTemplate<String, Object> redisTemplate;

    // Dependency Inversion (SOLID-D) — this class depends on the
    // UserServiceClient interface (Resilience4j circuit breaker already
    // wraps its methods), never on WebClient directly. Cache-miss fetches
    // below inherit that circuit breaker's protection for free.
    private final UserServiceClient userServiceClient;

    // Options pattern (T023/T021) — one bean bound from application.yml's
    // "discovery.cache" section (see DiscoveryProperties), the single place
    // every TTL below is spelled out as a number.
    private final DiscoveryProperties discoveryProperties;

    // -----------------------------------------------------------------
    // Session location — scan:{session_id}:location, 30s TTL.
    // Ephemeral by design (Location Privacy, CLAUDE.md): a live GPS fix is
    // never written to SQL, only ever held here with a short TTL so a client
    // that goes silent mid-scan can't leak a stale position forever.
    // -----------------------------------------------------------------

    public void cacheSessionLocation(Long sessionId, ScanLocation location) {
        redisTemplate.opsForValue().set(
                locationKey(sessionId),
                location,
                Duration.ofSeconds(discoveryProperties.cache().sessionLocationTtlSeconds()));
    }

    // Returns null on a miss (key absent/expired past the 30s TTL) — callers
    // treat that as "no live GPS fix right now", never as a zero-value
    // location.
    public ScanLocation getSessionLocation(Long sessionId) {
        Object raw = redisTemplate.opsForValue().get(locationKey(sessionId));
        return raw instanceof ScanLocation location ? location : null;
    }

    // Explicit delete, not left to expire on its own: /scan/stop's contract
    // is "the key is gone right away", not "gone within 30s" — the TTL above
    // is a safety net for a client that never calls stop, not a substitute
    // for this call.
    public void removeSessionLocation(Long sessionId) {
        redisTemplate.delete(locationKey(sessionId));
    }

    // -----------------------------------------------------------------
    // Nearby results — scan:{session_id}:nearby, 10s TTL.
    // -----------------------------------------------------------------

    // Returns null on a MISS so the caller can tell "nothing cached yet"
    // apart from "cached, and it happens to be an empty list" (a real, valid
    // result when nobody is nearby).
    @SuppressWarnings("unchecked")
    public List<UserCardResponse> getNearbyResults(Long sessionId) {
        Object cached = redisTemplate.opsForValue().get(nearbyKey(sessionId));
        return cached instanceof List<?> ? (List<UserCardResponse>) cached : null;
    }

    public void cacheNearbyResults(Long sessionId, List<UserCardResponse> results) {
        redisTemplate.opsForValue().set(
                nearbyKey(sessionId),
                results,
                Duration.ofSeconds(discoveryProperties.cache().nearbyResultTtlSeconds()));
    }

    // -----------------------------------------------------------------
    // Active sessions — scan:active_sessions, a managed Set, no TTL. A
    // session counts as "looking" for exactly as long as it is a member of
    // this Set — added at /scan/start, removed at /scan/stop, never left to
    // expire on a timer the way the TTL'd caches above are.
    // -----------------------------------------------------------------

    public void addActiveSession(Long sessionId) {
        redisTemplate.opsForSet().add(ACTIVE_SESSIONS_KEY, String.valueOf(sessionId));
    }

    public void removeActiveSession(Long sessionId) {
        redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_KEY, String.valueOf(sessionId));
    }

    // Parsing raw Set members back into Long ids used to be duplicated in
    // both ScanQueryServiceImpl and BleTokenServiceImpl (two near-identical
    // loops over the same Redis Set) — that mechanics belongs here, one
    // place, next to the SADD/SREM calls that populate the Set in the first
    // place, not repeated at every call site that reads it.
    public Set<Long> getActiveSessions() {
        Set<Object> members = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_KEY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> sessionIds = new HashSet<>();
        for (Object raw : members) {
            sessionIds.add(Long.valueOf(String.valueOf(raw)));
        }
        return sessionIds;
    }

    // -----------------------------------------------------------------
    // Eligible users — scan:eligible_users, a managed Set, no TTL. Written
    // exclusively by Kafka consumers (T025) reacting to user.verified /
    // trust.score.updated events from OTHER services — never by a live HTTP
    // query path. CLAUDE.md's "Discovery scans must only show verified,
    // active users" gate.
    // -----------------------------------------------------------------

    // SADD is naturally idempotent (adding an existing member is a no-op);
    // the ProcessedEventsRepository check upstream in the Kafka listener is
    // still what makes "processed twice" a deliberate guarantee rather than
    // an accident of Set semantics.
    public void addEligibleUser(String userId) {
        redisTemplate.opsForSet().add(ELIGIBLE_USERS_KEY, userId);
    }

    // trust.score.updated's suspension branch: request_limit == 0 removes a
    // user from the gate immediately (CLAUDE.md: "Very low scores suspend
    // from discovery"). SREM on a member never added is also a safe no-op.
    public void removeEligibleUser(String userId) {
        redisTemplate.opsForSet().remove(ELIGIBLE_USERS_KEY, userId);
    }

    public Set<String> getEligibleUsers() {
        Set<Object> members = redisTemplate.opsForSet().members(ELIGIBLE_USERS_KEY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<String> userIds = new HashSet<>();
        for (Object raw : members) {
            userIds.add(String.valueOf(raw));
        }
        return userIds;
    }

    // SISMEMBER convenience beyond the ticket's own method list: an O(1)
    // single-member check the discovery query's per-candidate eligibility
    // gate (ScanQueryServiceImpl, ScanSessionServiceImpl) needs on every
    // candidate — pulling the whole Set via getEligibleUsers() just to call
    // .contains() on it would be O(n) per candidate for no reason.
    public boolean isEligible(String userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(ELIGIBLE_USERS_KEY, userId));
    }

    // Pattern: Redis Hash — trust metadata is a small record of named fields
    // (score, badge, limit) looked up by one key (the user), not a single
    // opaque value or a Set member, so it gets its own data structure rather
    // than being force-fit into one of the two above. Kept here rather than
    // split into a fourth class: it is written by the same Kafka consumer
    // (trust.score.updated) that also calls removeEligibleUser, and this
    // ticket's whole point is "all Redis interactions in one place," not
    // "one class per topic."
    public void cacheTrustMetadata(String userId, Double newScore, String badgeLevel, Integer requestLimit) {
        redisTemplate.opsForHash().putAll(trustKey(userId), Map.of(
                TRUST_SCORE_FIELD, newScore,
                BADGE_LEVEL_FIELD, badgeLevel,
                REQUEST_LIMIT_FIELD, requestLimit));
    }

    // -----------------------------------------------------------------
    // Profile cache — user:{id}:profile_cache, 5 min TTL. True Cache-Aside:
    // check Redis first, only call User Service on a genuine miss.
    // -----------------------------------------------------------------

    // distanceKm is caller-position-dependent, never part of what's cached —
    // it is recomputed by the caller on every query and stitched onto
    // whichever profile fields (cached or freshly fetched) come back, so a
    // profile cached for one query's distance is never served stale for a
    // later one.
    public UserCardResponse getProfile(Long userId, double distanceKm) {
        Object cached = redisTemplate.opsForValue().get(profileKey(userId));
        if (cached instanceof UserCardResponse card) {
            return withDistance(card, distanceKm);
        }

        // Cache miss: fall through to User Service. UserServiceClientImpl's
        // getUserCard is @CircuitBreaker-wrapped but declares NO
        // fallbackMethod (T027) — a circuit-open or call failure reaches
        // this catch as a thrown exception rather than being swallowed
        // there, precisely so THIS class (the one T026 already made the
        // sole owner of Redis access) is the one that decides what a failed
        // call degrades to, instead of duplicating that Redis-aware decision
        // in two places.
        try {
            UserCardResponse fetched = userServiceClient.getUserCard(userId, distanceKm);
            if (fetched != null) {
                cacheProfile(userId, fetched);
            }
            return fetched;
        } catch (Exception ex) {
            // Pattern: Circuit Breaker fallback (Decorator under the hood) —
            // like a business that switches to a backup supplier the moment
            // its usual one stops delivering reliably, instead of placing
            // (and losing) yet another order and waiting out the timeout:
            // once "userService" has tripped (or even before it trips, on
            // any single call failure — see this method's own AC), every
            // discovery request that would otherwise wait out a full
            // WebClient timeout gets an immediate answer instead. Never log
            // level below WARN: a real User Service outage must be visible
            // here, not just inferable from a sudden wave of placeholder
            // cards on the radar screen.
            log.warn("User Service call failed for user {} ({}); degrading to cached-or-placeholder profile",
                    userId, ex.toString());
            return degradedProfile(userId, distanceKm);
        }
    }

    // T027's two-step fallback, in the order the ticket specifies: a cached
    // profile if one exists, else a minimal placeholder. Re-checking Redis
    // here (rather than trusting the miss already found above) matters only
    // in the narrow window where a different concurrent /scan/nearby request
    // for the SAME candidate populated the cache between that first read and
    // this failure — an unlikely but free-to-handle race, not the common
    // case. The placeholder itself is deliberately never passed to
    // cacheProfile: it is a best-effort stand-in for THIS one response, not
    // a fact about the user worth remembering for the next 5 minutes.
    private UserCardResponse degradedProfile(Long userId, double distanceKm) {
        Object cached = redisTemplate.opsForValue().get(profileKey(userId));
        if (cached instanceof UserCardResponse card) {
            return withDistance(card, distanceKm);
        }
        return new UserCardResponse(userId, "Unknown", null, false, distanceKm, true);
    }

    // distanceKm is caller-position-dependent (see this class's earlier
    // comment on why it is never part of what gets cached) — every read of a
    // cached UserCardResponse, whether a clean cache hit or the fallback's
    // own re-check, needs to restitch the CURRENT query's distance onto
    // whatever profile fields were cached, so this one-liner replaces two
    // near-identical reconstructions that used to drift independently.
    private static UserCardResponse withDistance(UserCardResponse card, double distanceKm) {
        return new UserCardResponse(card.id(), card.name(), card.photoUrl(), card.identityBadge(), distanceKm, card.unavailable());
    }

    public void cacheProfile(Long userId, UserCardResponse card) {
        redisTemplate.opsForValue().set(
                profileKey(userId),
                card,
                Duration.ofMinutes(discoveryProperties.cache().userProfileTtlMinutes()));
    }

    // -----------------------------------------------------------------
    // Blocklist cache — user:{id}:blocklist_cache, 5 min TTL. Same
    // Cache-Aside shape as profile above.
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<Long> getBlocklist(Long userId) {
        // Bug fix (found via manual testing, see testing-bugs-pending.md):
        // this read used to be unguarded. GenericJackson2JsonRedisSerializer
        // wraps every List value with a type-id ("[\"java.util.ArrayList\",
        // [...]]") so it knows what to rebuild on the way back out — but
        // List.of() (returned by getBlockListFallback below, since User
        // Service's /blocks endpoint doesn't exist yet) is a JDK-internal
        // ImmutableCollections type, not a plain ArrayList, and round-trips
        // through that same wrapper as a bare "[]" with no type id at all.
        // Reading it back then throws mid-deserialization, before this method
        // ever produces a List for a caller to even call .contains() on — a
        // cache is an optimization, and a value it can't read back must
        // degrade to "treat it as a miss," the same as the fetch-failure
        // catch block below already does, never a new way for the whole
        // /scan/nearby request to fail that a cold cache wouldn't have.
        Object cached;
        try {
            cached = redisTemplate.opsForValue().get(blocklistKey(userId));
        } catch (Exception ex) {
            log.warn("Blocklist cache read failed for user {} ({}); treating as a cache miss",
                    userId, ex.toString());
            cached = null;
        }

        if (cached instanceof List<?> list) {
            return (List<Long>) list;
        }

        // Fail-open by contract, same reasoning as UserServiceClientImpl's
        // own fallback: block-list data being unavailable must never fail
        // the whole /scan/nearby request. Caching the empty result for the
        // full TTL is deliberate too — User Service's /blocks endpoint is a
        // known gap (see UserServiceClient), so this is what keeps a
        // persistently-missing endpoint from being hammered on every single
        // candidate, every query.
        try {
            List<Long> fetched = userServiceClient.getBlockList(userId);
            cacheBlocklist(userId, fetched);
            return fetched;
        } catch (Exception ex) {
            log.warn("Blocklist fetch failed for user {} ({}); degrading to empty list (fail-open, not cached)",
                    userId, ex.toString());
            return List.of();
        }
    }

    public void cacheBlocklist(Long userId, List<Long> blockedUserIds) {
        redisTemplate.opsForValue().set(
                blocklistKey(userId),
                blockedUserIds,
                Duration.ofMinutes(discoveryProperties.cache().blocklistTtlMinutes()));
    }

    // -----------------------------------------------------------------
    // Key builders — private. This is the actual payoff of consolidation:
    // nobody outside this class constructs a Redis key string anymore, so a
    // key format can change in exactly one place instead of being hunted
    // down across every controller/service that used to type it out again.
    // -----------------------------------------------------------------

    private static String locationKey(Long sessionId) {
        return "scan:%d:location".formatted(sessionId);
    }

    private static String nearbyKey(Long sessionId) {
        return "scan:%d:nearby".formatted(sessionId);
    }

    private static String profileKey(Long userId) {
        return "user:%d:profile_cache".formatted(userId);
    }

    private static String blocklistKey(Long userId) {
        return "user:%d:blocklist_cache".formatted(userId);
    }

    private static String trustKey(String userId) {
        return "user:trust:%s".formatted(userId);
    }
}
