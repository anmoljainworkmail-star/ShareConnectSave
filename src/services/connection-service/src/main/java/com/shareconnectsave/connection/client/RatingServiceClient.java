package com.shareconnectsave.connection.client;

// Dependency Inversion (SOLID-D): InMemoryRequestLimitCache depends on this
// interface, never on RatingServiceClientImpl or WebClient directly - same
// shape as discovery-service's UserServiceClient (see that interface's own
// comment for the full rationale: a test double, or a future transport
// swap, only ever needs to satisfy this interface, not the concrete class).
//
// Cache-Aside (T045): this is the synchronous "read-through" half of
// InMemoryRequestLimitCache.getLimit()'s Cache-Aside miss path - see that
// class's own comment for why a cache miss must now call out to Rating
// Service instead of silently defaulting to DEFAULT_REQUEST_LIMIT (the gap
// T033 left open, closed by this ticket).
public interface RatingServiceClient {

    // userId is a plain Long, not the String the ticket's own interface
    // sketch used - every service in THIS codebase (not the stale
    // contracts/openapi/*.yaml files, which still say uuid) already
    // addresses users by Long id, same deviation note
    // UserServiceClientImpl's BlockedUserDto comment already documents for
    // discovery-service.
    TrustData getTrustData(Long userId);

    // Interface Segregation (SOLID-I): TrustData carries only requestLimit -
    // the one field InMemoryRequestLimitCache (today's only caller) actually
    // reads from Rating Service's GET /trust/:userId response. That response
    // also carries trust_score/is_trusted/rating_count (see Rating Service's
    // TrustScoreQueryResponse), but this client doesn't force its one caller
    // to depend on fields nothing here reads - a future caller that needs
    // more widens this record (or adds a new method) when it actually
    // exists, not speculatively now.
    //
    // fromFallback distinguishes a REAL answer from Rating Service from the
    // Circuit Breaker's fallback default - see
    // RatingServiceClientImpl.fallbackGetTrustData and
    // InMemoryRequestLimitCache.getLimit() for why that distinction is what
    // stops a transient outage's fallback value from being cached as if it
    // were real, event-sourced data.
    record TrustData(int requestLimit, boolean fromFallback) {
    }
}
