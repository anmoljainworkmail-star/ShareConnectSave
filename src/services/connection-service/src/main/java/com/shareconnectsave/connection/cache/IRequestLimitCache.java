package com.shareconnectsave.connection.cache;

// Dependency Inversion (SOLID-D): ConnectionServiceImpl depends on this
// interface, never on InMemoryRequestLimitCache directly. Nothing about the
// read path (createConnection's throttle check) needs to know whether the
// limit lives in a ConcurrentHashMap today or, later, in Redis so the value
// survives a pod restart / is shared across replicas — swapping the @Service
// bean behind this interface is the only change either evolution requires.
//
// Event-Driven Architecture: this cache's values are never fetched
// synchronously from Trust/Rating Service. T033's trust.score.updated Kafka
// consumer is the only writer (via update()); this ticket's read path
// (getLimit()) is written against that eventual-consistency contract from
// day one, even though nothing populates it yet.
public interface IRequestLimitCache {

    int getLimit(Long userId);

    void update(Long userId, int limit);
}
