package com.shareconnectsave.connection.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shareconnectsave.connection.client.RatingServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Default IRequestLimitCache implementation.
//
// Cache-Aside (T045 rework): T033's TrustScoreUpdatedConsumer remains a
// WRITER (via update()) whenever a trust.score.updated event happens to
// arrive, but getLimit() is no longer write-only on the read side. This is
// now the textbook Cache-Aside shape used elsewhere in this project
// (Discovery Service's Redis profile cache): check the cache first; on a
// miss, synchronously fetch the real value from the system of record
// (Rating Service, via RatingServiceClient) and store it before returning.
// This closes a real gap T033 left open: a pod restart used to wipe this
// cache and silently re-permission every low-trust user to
// DEFAULT_REQUEST_LIMIT until Rating Service happened to republish an event
// for them; with multiple replicas sharing one Kafka consumer group, a
// replica that never consumed a given user's partition never learned their
// real limit at all. See this ticket's own "Why this ticket exists" section
// for the full failure-mode writeup.
//
// Caffeine, not a bare ConcurrentHashMap: a bare map has no eviction, so
// every user who ever sends a connection request stays resident in this
// JVM's heap forever — an unbounded cache is a slow memory leak, not a
// cache. Caffeine gives two independent bounds instead:
//   - maximumSize: a hard ceiling on entry count (size-based eviction,
//     least-recently-used first) so a pathological number of distinct
//     userIds can never grow this map without limit.
//   - expireAfterWrite: entries older than this are evicted even if still
//     within maximumSize — trust scores change slowly, but this bounds how
//     long a stale limit survives without this service seeing either a
//     fresh trust.score.updated event or a fresh cache-miss re-fetch for
//     that user.
// Both are read from IRequestLimitCache callers as a plain int; nothing
// about ConnectionServiceImpl or TrustScoreUpdatedConsumer needs to know
// Caffeine (vs. a raw map) is behind this interface — same Dependency
// Inversion the interface's own comment already documents.
//
// Thread safety: Caffeine's Cache is internally synchronized for concurrent
// get/put, the same guarantee ConcurrentHashMap gave — HTTP request threads
// call getLimit() during createConnection, and a single Kafka consumer
// thread calls update() whenever trust.score.updated arrives, with no other
// coordination between them.
@Component
@Slf4j
@RequiredArgsConstructor
public class InMemoryRequestLimitCache implements IRequestLimitCache {

    public static final int DEFAULT_REQUEST_LIMIT = 5;

    // Trust score updates are infrequent (only on new ratings) — 24h is far
    // longer than any realistic re-publish interval, so this TTL exists to
    // bound staleness/leakage, not to force frequent re-fetching.
    private static final Duration ENTRY_TTL = Duration.ofHours(24);
    private static final long MAX_ENTRIES = 100_000;

    private final Cache<Long, Integer> limitsByUserId = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(ENTRY_TTL)
            .build();

    // T045: the synchronous read-through collaborator for the Cache-Aside
    // miss path below. Dependency Inversion (SOLID-D) - this field is typed
    // as the interface, never RatingServiceClientImpl, so a test can
    // substitute any implementation.
    private final RatingServiceClient ratingServiceClient;

    @Override
    public int getLimit(Long userId) {
        Integer cached = limitsByUserId.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }

        // Cache-Aside miss: ask Rating Service for the real answer instead
        // of silently defaulting (CLAUDE.md's "What NOT to do" list for this
        // ticket is explicit - do not jump straight to calling Rating
        // Service every time, but ALSO do not skip this call on a genuine
        // miss). RatingServiceClientImpl's @CircuitBreaker means this call
        // either returns a live result or, on circuit-open/failure, the
        // fallback TrustData below - getLimit() itself never has to
        // try/catch anything.
        RatingServiceClient.TrustData trustData = ratingServiceClient.getTrustData(userId);

        if (trustData.fromFallback()) {
            // Circuit Breaker fallback — NOT written back into this cache.
            // Same principle this class already applies to its event-driven
            // write path: "a fallback value is never itself written back
            // into the cache, so it can't masquerade as a real,
            // event-sourced limit later." If this were cached, a transient
            // Rating Service outage would pin this user at
            // DEFAULT_REQUEST_LIMIT for up to ENTRY_TTL (24h) even after
            // Rating Service recovers and the circuit closes again -
            // defeating the whole point of asking again next time instead of
            // trusting a stale guess.
            log.debug("Cache-Aside miss for user {} resolved via circuit-breaker fallback; not caching", userId);
            return trustData.requestLimit();
        }

        log.debug("Cache-Aside miss for user {} resolved via Rating Service; caching requestLimit={}",
                userId, trustData.requestLimit());
        limitsByUserId.put(userId, trustData.requestLimit());
        return trustData.requestLimit();
    }

    @Override
    public void update(Long userId, int limit) {
        // TrustScoreUpdatedConsumer is this method's only caller — the
        // single writer keeping this cache warm, per that class's own
        // Single Responsibility comment.
        limitsByUserId.put(userId, limit);
    }
}
