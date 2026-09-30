package com.shareconnectsave.connection.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Default IRequestLimitCache implementation.
//
// Cache-Aside (event-driven cousin): T033's TrustScoreUpdatedConsumer is the
// only WRITER (via update()) — there is no lazy "fetch from Rating Service
// on miss" path here, unlike the classic Cache-Aside example elsewhere in
// this project (Discovery Service's Redis profile cache, populated on a read
// miss). This cache is kept warm purely by reacting to events; getLimit()
// only ever reads whatever the event feed has put there, falling back to a
// platform default for a user this service has never seen a
// trust.score.updated event for.
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
//     long a stale limit survives if Rating Service ever stops publishing
//     for a user (e.g. they went inactive) without this service noticing.
// Both are read from IRequestLimitCache callers as a plain int via
// getOrDefault-shaped logic; nothing about ConnectionServiceImpl or
// TrustScoreUpdatedConsumer needs to know Caffeine (vs. a raw map) is behind
// this interface — same Dependency Inversion the interface's own comment
// already documents.
//
// Thread safety: Caffeine's Cache is internally synchronized for concurrent
// get/put, the same guarantee ConcurrentHashMap gave — HTTP request threads
// call getLimit() during createConnection, and a single Kafka consumer
// thread calls update() whenever trust.score.updated arrives, with no other
// coordination between them.
//
// Still in-memory only: a pod restart resets every user back to
// DEFAULT_REQUEST_LIMIT until Rating Service happens to re-publish their
// score. Acceptable for this ticket's scope — a Redis-backed implementation
// behind this same IRequestLimitCache interface is the natural upgrade path
// later, with zero change to ConnectionServiceImpl or the consumer.
@Component
public class InMemoryRequestLimitCache implements IRequestLimitCache {

    public static final int DEFAULT_REQUEST_LIMIT = 5;

    // Trust score updates are infrequent (only on new ratings) — 24h is far
    // longer than any realistic re-publish interval, so this TTL exists to
    // bound staleness/leakage, not to force frequent re-fetching (there is
    // no "re-fetch" here at all; a miss just falls back to the default).
    private static final Duration ENTRY_TTL = Duration.ofHours(24);
    private static final long MAX_ENTRIES = 100_000;

    private final Cache<Long, Integer> limitsByUserId = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(ENTRY_TTL)
            .build();

    @Override
    public int getLimit(Long userId) {
        // getIfPresent + manual fallback, not Caffeine's own
        // get(key, mappingFunction): any user this cache has never seen a
        // trust.score.updated event for (i.e. everyone, until Rating
        // Service/T033 actually publishes one for them) falls back to the
        // platform default rather than throwing or silently allowing
        // unlimited requests — and a fallback value is never itself written
        // back into the cache, so it can't masquerade as a real,
        // event-sourced limit later.
        Integer cached = limitsByUserId.getIfPresent(userId);
        return cached != null ? cached : DEFAULT_REQUEST_LIMIT;
    }

    @Override
    public void update(Long userId, int limit) {
        // TrustScoreUpdatedConsumer is this method's only caller — the
        // single writer keeping this cache warm, per that class's own
        // Single Responsibility comment.
        limitsByUserId.put(userId, limit);
    }
}
