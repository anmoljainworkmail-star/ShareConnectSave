package com.shareconnectsave.admin_service.client;

import java.math.BigDecimal;

// Dependency Inversion (SOLID-D) + Interface Segregation (SOLID-I) — see
// UserServiceClient's own class comment for both. admin-service only ever
// needs TWO calls out of Rating Service's surface (the current trust score,
// and — as of T058 — forwarding an admin's override), so this interface
// exposes exactly those, nothing more.
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

    // T058 — PATCH /admin/users/{id}/trust-score forwards here. String
    // userId (not UUID, despite the ticket's own illustrative signature) to
    // match getTrustScore above and every other client method in this
    // interface — Rating Service's ids are numeric Longs on the wire
    // already converted to String at this client's boundary (see
    // RatingServiceClientImpl's class comment), so a UUID type here would be
    // a type this platform's ids never actually are.
    //
    // STUBBED at the implementation, not at this interface — see
    // RatingServiceClientImpl.overrideTrustScore's own comment for why: the
    // Tech Lead's decision was to build this call's shape for real now and
    // only short-circuit the actual outbound HTTP call, so the day Rating
    // Service ships PATCH /trust/:userId, only the impl file changes and
    // every caller (AdminTrustScoreServiceImpl, this interface) stays
    // untouched — Liskov Substitution (SOLID-L): the stub and the eventual
    // real implementation must be interchangeable to every caller.
    TrustScoreOverrideResult overrideTrustScore(String userId, TrustScoreOverrideRequest request);

    record TrustScoreSnapshot(String userId, double trustScore, int requestLimit, boolean trusted) {
    }

    // Outbound request shape to Rating Service's future PATCH /trust/:userId
    // — deliberately separate from admin-service's own inbound
    // TrustScoreOverrideDto (dto package): this record is what crosses the
    // service boundary to Rating Service, the Dto is what crosses the
    // boundary from the Angular PWA — collapsing the two would make a future
    // wire-shape change on either side accidentally ripple into the other,
    // the same "no outward-facing contract drift" reasoning UserServiceClient's
    // nested DTOs already document.
    record TrustScoreOverrideRequest(BigDecimal score, String reason) {
    }

    // What Rating Service is expected to echo back once PATCH /trust/:userId
    // is real. The stub constructs this locally for now (see
    // RatingServiceClientImpl) by echoing the request straight back — not a
    // guess at Rating Service's eventual response shape, just the minimum
    // this client's own caller (AdminTrustScoreServiceImpl) needs today.
    record TrustScoreOverrideResult(String userId, BigDecimal score, String reason) {
    }
}
