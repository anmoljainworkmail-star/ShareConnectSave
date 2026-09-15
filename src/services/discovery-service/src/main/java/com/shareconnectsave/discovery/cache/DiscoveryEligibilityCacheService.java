package com.shareconnectsave.discovery.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Pattern: Single Responsibility (SOLID-S) + Cache-Aside — a deliberately
// SEPARATE class from ScanCacheService, not an addition to it.
// ScanCacheService's own header comment already scopes itself to ONLY the
// nearby-result cache, block-list cache, and scan:active_sessions Set — the
// two Redis structures this class owns (scan:eligible_users, per-user trust
// metadata) are written exclusively by Kafka consumers (T025) reacting to
// events from OTHER services, never by the GPS query path T023 already
// covers. Folding them into ScanCacheService would blur "cache populated by
// a live HTTP query" with "cache populated by an asynchronous event", two
// genuinely different write paths that happen to share a Redis instance.
//
// scan:eligible_users is the gate CLAUDE.md describes: "Discovery scans must
// only show verified, active users" — a session appearing in
// scan:active_sessions (ScanCacheService) is necessary but not sufficient;
// this Set is the other half of that check. No query in this ticket's scope
// reads it yet (that wiring is a separate, not-yet-approved ticket) — T025's
// job is only to keep the cache itself correct as user.verified /
// trust.score.updated events arrive.
@Component
@RequiredArgsConstructor
public class DiscoveryEligibilityCacheService {

    // Single source of truth for this literal, same convention as
    // ScanCacheService.ACTIVE_SESSIONS_KEY — every read/write of this Set
    // goes through this constant, never a re-typed string literal.
    public static final String ELIGIBLE_USERS_KEY = "scan:eligible_users";

    private static final String TRUST_SCORE_FIELD = "new_score";
    private static final String BADGE_LEVEL_FIELD = "badge_level";
    private static final String REQUEST_LIMIT_FIELD = "request_limit";

    private final RedisTemplate<String, Object> redisTemplate;

    // user.verified's entire side effect: SADD is naturally idempotent at the
    // Redis level (adding an existing member is a no-op), but the
    // ProcessedEventsRepository check in UserVerifiedEventListener is still
    // what makes "processed twice" a deliberate guarantee rather than an
    // accident of Set semantics — see that class's own comment.
    public void addEligible(String userId) {
        redisTemplate.opsForSet().add(ELIGIBLE_USERS_KEY, userId);
    }

    // Eligibility gate read: used by ScanQueryServiceImpl to check whether a
    // candidate user is verified and not suspended (request_limit > 0). A
    // candidate not in this Set is filtered out and never appears in nearby
    // results, regardless of geospatial metrics.
    public boolean isEligible(String userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(ELIGIBLE_USERS_KEY, userId));
    }

    // trust.score.updated's suspension branch: request_limit == 0 removes
    // the user from the gate immediately (CLAUDE.md: "Very low scores
    // suspend from discovery"). SREM on a member that was never added is
    // also a safe no-op.
    public void removeEligible(String userId) {
        redisTemplate.opsForSet().remove(ELIGIBLE_USERS_KEY, userId);
    }

    // Pattern: Redis Hash — a different Redis data structure from the Sets/
    // Strings/Lists ScanCacheService already demonstrates, chosen here
    // because trust metadata is naturally a small record of named fields
    // (score, badge, limit) looked up by one key (the user), not a single
    // opaque value or a set of members. opsForHash().putAll writes all three
    // fields in one round trip instead of three separate opsForValue calls.
    public void cacheTrustMetadata(String userId, Double newScore, String badgeLevel, Integer requestLimit) {
        redisTemplate.opsForHash().putAll(trustKey(userId), Map.of(
                TRUST_SCORE_FIELD, newScore,
                BADGE_LEVEL_FIELD, badgeLevel,
                REQUEST_LIMIT_FIELD, requestLimit));
    }

    public static String trustKey(String userId) {
        return "user:trust:%s".formatted(userId);
    }
}
