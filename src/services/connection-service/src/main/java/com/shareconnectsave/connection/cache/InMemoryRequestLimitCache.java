package com.shareconnectsave.connection.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

// Default IRequestLimitCache implementation. ConcurrentHashMap, not a plain
// HashMap: this bean is a Spring singleton (one instance for the whole
// application), and its two methods will be called from two different
// threads with no other coordination between them — HTTP request threads
// calling getLimit() during createConnection, and (from T033 onward) a
// single Kafka consumer thread calling update() whenever
// trust.score.updated arrives. ConcurrentHashMap gives thread-safe reads and
// writes without this class taking out its own lock, which a plain HashMap
// would need (or silently corrupt under concurrent modification without
// one).
//
// In-memory only: a pod restart resets every user back to
// DEFAULT_REQUEST_LIMIT until Rating Service happens to re-publish their
// score. Acceptable for this ticket's scope — T030 only needs the
// read-a-cache SHAPE to exist; a Redis-backed implementation behind the same
// IRequestLimitCache interface is the natural upgrade path later, with zero
// change to ConnectionServiceImpl.
@Component
public class InMemoryRequestLimitCache implements IRequestLimitCache {

    public static final int DEFAULT_REQUEST_LIMIT = 5;

    private final ConcurrentHashMap<Long, Integer> limitsByUserId = new ConcurrentHashMap<>();

    @Override
    public int getLimit(Long userId) {
        // getOrDefault, not get()+null-check: any user T033 has never
        // published a trust.score.updated event for (i.e. everyone, until
        // that ticket ships) falls back to the platform default rather than
        // throwing or silently allowing unlimited requests.
        return limitsByUserId.getOrDefault(userId, DEFAULT_REQUEST_LIMIT);
    }

    @Override
    public void update(Long userId, int limit) {
        // Unused by anything in this ticket — T033's Kafka consumer is the
        // intended (future) caller. Written now so this class's contract is
        // complete and that consumer needs no change to this file when it
        // lands.
        limitsByUserId.put(userId, limit);
    }
}
