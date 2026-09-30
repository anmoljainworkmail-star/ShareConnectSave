package com.shareconnectsave.connection.cache;

// Dependency Inversion (SOLID-D): ConnectionServiceImpl depends on this
// interface, never on InMemoryRequestLimitCache directly. Nothing about the
// read path (createConnection's throttle check) needs to know whether the
// limit lives in a Caffeine cache today or, later, in Redis so the value
// survives a pod restart / is shared across replicas — swapping the @Service
// bean behind this interface is the only change either evolution requires.
//
// Event-Driven Architecture: this cache's values are never fetched
// synchronously from Trust/Rating Service. TrustScoreUpdatedConsumer (T033)
// is the only writer (via update()); createConnection's read path
// (getLimit()) was written against that eventual-consistency contract from
// day one (T030), before that consumer existed to populate it.
public interface IRequestLimitCache {

    int getLimit(Long userId);

    void update(Long userId, int limit);
}
