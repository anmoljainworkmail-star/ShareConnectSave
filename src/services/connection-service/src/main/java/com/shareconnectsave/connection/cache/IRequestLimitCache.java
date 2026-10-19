package com.shareconnectsave.connection.cache;

// Dependency Inversion (SOLID-D): ConnectionServiceImpl depends on this
// interface, never on InMemoryRequestLimitCache directly. Nothing about the
// read path (createConnection's throttle check) needs to know whether the
// limit lives in a Caffeine cache today or, later, in Redis so the value
// survives a pod restart / is shared across replicas — swapping the @Service
// bean behind this interface is the only change either evolution requires.
//
// Cache-Aside Pattern (T045 rework): values are fetched synchronously from
// Rating Service (via RatingServiceClient) on a cache miss, not only via
// event-driven updates. TrustScoreUpdatedConsumer (T033) remains an
// independent WRITER (via update()) whenever a trust.score.updated event
// arrives, providing event-driven refresh alongside the cache-miss
// synchronous fetch. This closes the T033 failure modes: pod restart no
// longer silently resets low-trust users, and scale-out replicas missing
// a partition now re-fetch on their first getLimit() call.
public interface IRequestLimitCache {

    int getLimit(Long userId);

    void update(Long userId, int limit);
}
