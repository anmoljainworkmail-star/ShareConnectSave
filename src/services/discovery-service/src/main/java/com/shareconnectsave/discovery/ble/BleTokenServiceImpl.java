package com.shareconnectsave.discovery.ble;

import com.shareconnectsave.discovery.ble.domain.BleSeed;
import com.shareconnectsave.discovery.cache.DiscoveryCacheService;
import com.shareconnectsave.discovery.config.BleProperties;
import com.shareconnectsave.discovery.scan.ScanSessionRepository;
import com.shareconnectsave.discovery.scan.domain.ScanSession;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Pattern: Single Responsibility (SOLID-S) — this class only orchestrates
// BLE seed generation/resolution (random bytes, HMAC-based per-window
// derivation, expiry math, delegating profile/block-list lookups to
// DiscoveryCacheService); it owns no HTTP concerns (BleTokenController) and
// no scheduling concerns (BleTokenCleanupTask). Class name kept as-is per
// this ticket's own "Java classes" section — this is a rework of v1's
// service, not a replacement of it.
@Service
@Slf4j
@RequiredArgsConstructor
public class BleTokenServiceImpl implements BleTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SEED_BYTE_LENGTH = 32;

    // 16 of the full 32-byte HMAC-SHA256 output — classic BLE advertising
    // caps at 31 bytes total, so the full digest would not fit alongside the
    // app's Service UUID in a real broadcast payload. 128 bits remains
    // enormously collision-safe for this use case. Server and (future,
    // T065) client MUST truncate identically for a submitted value to ever
    // match bit-for-bit.
    private static final int BROADCAST_BYTE_LENGTH = 16;

    // Shared, thread-safe per the JDK's own SecureRandom contract — unlike
    // javax.crypto.Mac below, one instance is fine to reuse across requests.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BleSeedRepository bleSeedRepository;
    private final ScanSessionRepository scanSessionRepository;

    // T026: profile and block-list lookups (both used below) now go through
    // DiscoveryCacheService's Cache-Aside orchestration instead of calling
    // UserServiceClient directly — same consolidation ScanQueryServiceImpl's
    // GPS path already applies, so BLE resolve benefits from the same warm
    // cache without duplicating the get/miss/populate logic a second time.
    private final DiscoveryCacheService discoveryCacheService;
    private final BleProperties bleProperties;

    @Override
    @Transactional
    public String issueSeed(Long userId) {
        byte[] seedBytes = new byte[SEED_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(seedBytes);
        // URL-safe Base64, no padding: the raw seed travels as a JSON string
        // field (BleSeedResponse) and is stored back in this same encoding
        // in ble_seeds — a plain alphanumeric-safe encoding avoids
        // characters ('+', '/', '=') that complicate either.
        String rawSeed = Base64.getUrlEncoder().withoutPadding().encodeToString(seedBytes);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(bleProperties.seed().ttlMinutes(), ChronoUnit.MINUTES);

        // Spec step 2: "old tokens for this user (expired) are deleted" —
        // same per-user, inline-with-issuance spirit as v1, scoped to THIS
        // user and distinct from BleTokenCleanupTask's service-wide
        // scheduled sweep.
        bleSeedRepository.deleteByUserIdAndExpiresAtBefore(userId, now);

        // Rolling identifiers derived from a locally-held secret (the actual
        // concept behind Apple/Google's Exposure Notification system): this
        // seed, once handed to the client below, is the ONLY thing it needs
        // to keep rotating a broadcast value indefinitely, entirely offline
        // — no server round-trip required to stay "fresh", unlike v1's
        // 5-minute token.
        bleSeedRepository.save(BleSeed.builder()
                .userId(userId)
                .seed(rawSeed)
                .expiresAt(expiresAt)
                .build());

        return rawSeed;
    }

    @Override
    public List<UserCardResponse> resolveTokens(Long callerUserId, List<String> rawTokens) {
        Instant now = Instant.now();

        // The client computes this same integer window number from its own
        // wall clock (window = floor(unixEpochSeconds / ble.window.seconds))
        // — integer division on a non-negative epoch-seconds value already
        // floors, so no extra Math.floor call is needed here.
        long currentWindow = now.getEpochSecond() / bleProperties.window().seconds();

        // BLE range (CLAUDE.md: "10-30 m offline fallback") stands in for
        // GPS's computed distanceKm — see BleProperties.Range's own comment
        // for why this is a config-driven constant, not a measurement, in
        // this code path. Unchanged from v1.
        double distanceKm = bleProperties.range().meters() / 1000.0;

        // Pattern: cost-bounded verification via SCOPING, not indexing —
        // resolve can no longer do a single indexed hash lookup the way v1
        // did (nothing was pre-indexed against a value the server never saw
        // before this call), so the candidate pool is narrowed to
        // scan:active_sessions FIRST, the same Redis Set GPS discovery
        // already scopes itself to. Without this, resolve cost would grow
        // with every user who has ever registered a seed, not just people
        // plausibly nearby right now.
        List<Long> candidateUserIds = activeCandidateUserIds();
        if (candidateUserIds.isEmpty()) {
            return List.of();
        }

        // Batch-fetch, not one findByUserId() per candidate — same
        // "findAllById, not a loop of findById()" shape ScanQueryServiceImpl
        // uses for scan sessions. Expired seeds are filtered out here so an
        // expired candidate can never be matched, per this ticket's spec.
        List<BleSeed> candidateSeeds = bleSeedRepository.findByUserIdIn(candidateUserIds).stream()
                .filter(seed -> seed.getExpiresAt().isAfter(now))
                .toList();

        List<UserCardResponse> results = new ArrayList<>();

        // Once a candidate has been checked against one submitted value
        // (matched or not applicable — e.g. blocked), there is no reason to
        // recompute their HMAC again for a later submitted value in this
        // same request; this also keeps a single candidate from ever
        // appearing twice in the results if the caller submitted several
        // tokens that all happen to resolve to them.
        Set<Long> settledUserIds = new HashSet<>();

        for (String rawToken : rawTokens) {
            for (BleSeed candidateSeed : candidateSeeds) {
                Long candidateUserId = candidateSeed.getUserId();
                if (settledUserIds.contains(candidateUserId)) {
                    continue;
                }

                // Pattern: Idempotency & Silent Failure — an unknown or
                // non-matching value simply fails every comparison below;
                // this loop has no branch that raises for that case, it
                // just moves on to the next candidate/token. Two windows are
                // tried (current and previous) to tolerate a value generated
                // just before a rotation boundary — clock skew / network
                // delay between broadcast and resolve.
                if (!matchesEitherWindow(rawToken, candidateSeed.getSeed(), currentWindow)) {
                    continue;
                }

                settledUserIds.add(candidateUserId);

                // Same bidirectional block check as v1 and GPS discovery's
                // own isBlocked — a blocked/blocking candidate is silently
                // omitted, not a different response shape or error.
                if (isBlocked(callerUserId, candidateUserId) || isBlocked(candidateUserId, callerUserId)) {
                    break; // candidate settled (blocked) — stop scanning seeds for THIS submitted token
                }

                // Cache-Aside + Circuit Breaker Pattern (Decorator under the
                // hood) — same DiscoveryCacheService.getProfile the GPS query
                // path uses; a down/slow User Service degrades this candidate
                // to cached data or omission, never a failed request.
                UserCardResponse card = discoveryCacheService.getProfile(candidateUserId, distanceKm);
                if (card != null) {
                    results.add(card);
                }
                break; // this token resolved to a candidate — move on to the next submitted token
            }
        }

        return results;
    }

    // Resolves scan:active_sessions (the Redis Set of currently-scanning
    // session ids — same source ScanQueryServiceImpl.findNearby reads via
    // DiscoveryCacheService.getActiveSessions) down to the USER ids that own
    // those sessions, reusing ScanSessionRepository.findAllById exactly the
    // way ScanQueryServiceImpl already batch-fetches sessions by id — no new
    // query shape invented for this ticket. A session can be a member of
    // this Set without a matching active SQL row only through a bug
    // elsewhere (see ScanQueryServiceImpl's own comment on this); such a
    // session is silently skipped here rather than treated as a crash.
    private List<Long> activeCandidateUserIds() {
        Set<Long> activeSessionIds = discoveryCacheService.getActiveSessions();

        return scanSessionRepository.findAllById(activeSessionIds).stream()
                .filter(ScanSession::isActive)
                .map(ScanSession::getUserId)
                .toList();
    }

    private boolean matchesEitherWindow(String rawToken, String seed, long currentWindow) {
        return rawToken.equals(deriveBroadcastValue(seed, currentWindow))
                || rawToken.equals(deriveBroadcastValue(seed, currentWindow - 1));
    }

    // Must match the client's own derivation bit-for-bit (see this ticket's
    // "Client-side derivation" spec, T065's future job to implement) —
    // compute the full HMAC first, truncate to the first 16 bytes, THEN
    // encode. Any deviation from that exact order breaks interoperability.
    private String deriveBroadcastValue(String seed, long window) {
        byte[] seedBytes = Base64.getUrlDecoder().decode(seed);
        byte[] windowBytes = ByteBuffer.allocate(Long.BYTES).putLong(window).array();
        byte[] fullHmac = hmacSha256(seedBytes, windowBytes);
        byte[] truncated = Arrays.copyOf(fullHmac, BROADCAST_BYTE_LENGTH);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
    }

    // Cache-Aside Pattern, bidirectional — identical shape to
    // ScanQueryServiceImpl.isBlocked (GPS discovery's own block check):
    // a candidate only passes when NEITHER direction of the block
    // relationship exists. T026: delegates to DiscoveryCacheService's
    // getBlocklist instead of duplicating the get/miss/populate steps here.
    private boolean isBlocked(Long ownerUserId, Long otherUserId) {
        return discoveryCacheService.getBlocklist(ownerUserId).contains(otherUserId);
    }

    // Mac is explicitly documented as NOT thread-safe (unlike
    // MessageDigest/SecureRandom), so a fresh instance is created per call
    // rather than shared as a static field — cheap relative to the request's
    // own DB/HTTP work, and avoids synchronizing every hash under one lock.
    // Keyed by the per-user SEED itself, not bleProperties.seed().hmacSecret()
    // — see BleProperties.Seed's own comment for why: the client this must
    // interoperate with only ever receives the seed, never that config
    // value, so the seed is the only key that lets both sides derive the
    // identical broadcast value.
    private byte[] hmacSha256(byte[] keyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a standard JCE algorithm guaranteed present on
            // every JVM — this can only fire from a JVM/provider
            // misconfiguration, which is exactly the kind of failure that
            // should crash loudly rather than silently produce an unusable
            // hash.
            throw new IllegalStateException("Failed to compute BLE broadcast value HMAC", e);
        }
    }
}
