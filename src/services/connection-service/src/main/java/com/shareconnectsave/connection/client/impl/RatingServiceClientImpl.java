package com.shareconnectsave.connection.client.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shareconnectsave.connection.cache.InMemoryRequestLimitCache;
import com.shareconnectsave.connection.client.RatingServiceClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

// Pattern: Circuit Breaker (Decorator under the hood, per CLAUDE.md) -
// @CircuitBreaker wraps getTrustData transparently, tripping open after the
// "ratingService" instance's configured failure rate so a slow/down Rating
// Service can no longer block every createConnection() throttle check
// behind it (see application.yml's resilience4j.circuitbreaker.instances.ratingService
// block). Same annotation, same shape as discovery-service's
// UserServiceClientImpl.
//
// UNLIKE discovery-service's getUserCard (which deliberately omits a
// fallbackMethod so ITS caller, DiscoveryCacheService, can tell a genuine
// 404 apart from an infrastructure failure - see that class's T027 comment),
// this method DOES declare one, exactly as this ticket's Agent
// Implementation Notes specify. There is no second, differently-shaped
// outcome worth preserving here: InMemoryRequestLimitCache has exactly one
// thing to do whether Rating Service 500s, times out, or the circuit is
// already open - fall back to DEFAULT_REQUEST_LIMIT - so the fallback lives
// in the one class Resilience4j requires it to (the same class as the
// @CircuitBreaker-annotated method) rather than a try/catch duplicated at
// every call site.
@Component
@Slf4j
@RequiredArgsConstructor
public class RatingServiceClientImpl implements RatingServiceClient {

    private final WebClient ratingServiceWebClient;

    @Override
    @CircuitBreaker(name = "ratingService", fallbackMethod = "fallbackGetTrustData")
    public TrustData getTrustData(Long userId) {
        // Pattern: Cache-Aside with explicit 404 handling (T045). A user who
        // has never been rated is a normal outcome (GET /trust/{userId}
        // returns 404 when no trust_score row exists yet), NOT an
        // infrastructure failure. By handling 404 explicitly before
        // .bodyToMono(), we prevent WebClientResponseException.NotFound from
        // propagating as an uncaught exception into Resilience4j's failure
        // counter — 404 is a valid, cacheable result: "no score yet, use the
        // safe default", worth caching as TrustData with fromFallback=false
        // so a fresh getLimit() call after restart re-fetches the real limit
        // from Rating Service instead of silently defaulting and hoping for a
        // Kafka event to eventually populate it (the T033 failure mode this
        // ticket closes).
        TrustScoreWireResponse response = ratingServiceWebClient.get()
                .uri("/trust/{userId}", userId)
                .retrieve()
                .onStatus(status -> status.value() == 404, resp -> Mono.empty())
                .bodyToMono(TrustScoreWireResponse.class)
                // Virtual Threads (spring.threads.virtual.enabled: true) are
                // what make .block() safe here - this call runs on a cheap
                // JVM-managed virtual thread that unmounts from its OS
                // carrier while blocked on this round-trip, instead of
                // pinning one of a fixed pool of platform threads for the
                // whole call. Same reasoning as discovery-service's
                // UserServiceClientImpl.
                .block();

        if (response == null) {
            // 404: this user has never been rated. A valid outcome, not an
            // infrastructure failure. Return a normal (non-fallback) TrustData
            // so this value is cached by InMemoryRequestLimitCache — the
            // cache-miss codepath's contract requires a real answer, not a
            // circuit-breaker fallback. If the user is later rated,
            // trust.score.updated will update the cache or the next getLimit()
            // call will re-fetch. Using the same DEFAULT_REQUEST_LIMIT
            // constant as everywhere else in this file/service for
            // consistency.
            return new TrustData(InMemoryRequestLimitCache.DEFAULT_REQUEST_LIMIT, false);
        }

        return new TrustData(response.requestLimit(), false);
    }

    // Resilience4j's fallback-method contract: same parameter list as the
    // guarded method plus a trailing Throwable - this is how Resilience4j
    // matches a fallback to its @CircuitBreaker method at startup (a
    // mismatched signature fails fast with NoFallbackMethodFoundException,
    // not silently).
    //
    // Fail-open, by explicit Tech Lead decision (this ticket's "What NOT to
    // do" list): same posture T033 already shipped when this cache had no
    // read path at all. A Rating Service outage must never block connection
    // requests from being created - the alternative (fail-closed, refusing
    // every request while Rating Service is down) would turn one
    // dependency's outage into a platform-wide outage of an unrelated
    // feature. The exception is logged, never swallowed silently, so a real
    // outage is visible in logs rather than only inferable from everyone
    // suddenly having the default limit.
    private TrustData fallbackGetTrustData(Long userId, Throwable ex) {
        log.warn("Rating Service unavailable for user {} ({}); falling back to default request limit (fail-open)",
                userId, ex.toString());
        return new TrustData(InMemoryRequestLimitCache.DEFAULT_REQUEST_LIMIT, true);
    }

    // Nested, package-private - the exact wire shape Rating Service's
    // GET /trust/:userId returns (see rating-service's
    // TrustScoreQueryResponse). Only request_limit is deserialized here -
    // Interface Segregation (SOLID-I): this client doesn't need
    // trust_score/is_trusted/rating_count, so it doesn't declare fields for
    // them, the same "depend on precisely what you use" discipline
    // UserServiceClientImpl's own PublicUserProfileResponse already applies.
    private record TrustScoreWireResponse(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("request_limit") int requestLimit) {
    }
}
