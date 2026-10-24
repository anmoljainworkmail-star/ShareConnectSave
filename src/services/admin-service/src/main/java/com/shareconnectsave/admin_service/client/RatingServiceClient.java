package com.shareconnectsave.admin_service.client;

// Dependency Inversion (SOLID-D) + Interface Segregation (SOLID-I) — see
// UserServiceClient's own class comment for both. admin-service only ever
// needs ONE call out of Rating Service's surface (the current trust score),
// so this interface exposes exactly that, nothing more.
public interface RatingServiceClient {

    // Throws UpstreamUnavailableException on circuit-open/call-failure (see
    // that class's comment) — this method never returns null or a fabricated
    // score. Two call sites use this differently:
    //   - GET /admin/users/{id} and the queue's per-item enrichment (reads):
    //     ReviewQueueServiceImpl catches the exception and substitutes a
    //     trustScoreUnavailable=true placeholder (ticket's own "Partial-failure
    //     degradation" spec).
    //   - POST /admin/users/{id}/reinstate (a write's prerequisite): the
    //     exception is deliberately left to propagate uncaught, failing the
    //     whole reinstate request rather than publishing a trust.score.updated
    //     event with a fabricated number — see ReviewQueueServiceImpl.reinstate
    //     for why "don't fabricate data" applies even harder to an event this
    //     service is about to broadcast to every other service on Kafka.
    TrustScoreSnapshot getTrustScore(String userId);

    record TrustScoreSnapshot(String userId, double trustScore, int requestLimit, boolean trusted) {
    }
}
