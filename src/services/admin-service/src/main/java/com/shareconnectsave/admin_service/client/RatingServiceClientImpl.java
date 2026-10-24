package com.shareconnectsave.admin_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

// Circuit Breaker (Decorator), same shape as UserServiceClientImpl — wraps
// the one call this client makes (GET /trust/{userId}, T045's existing
// endpoint, called directly over the Docker network per that controller's own
// comment: "Admin Service is this endpoint's other documented caller").
//
// NOTE on the wire shape: contracts/openapi/rating-service.yaml's
// TrustScoreResponse schema (user_id, score, badge_level, request_limit,
// suspended) does NOT match what TrustScoreController actually returns
// (TrustScoreQueryResponse: user_id, trust_score, request_limit, is_trusted,
// rating_count — no "suspended" field exists on the real endpoint at all).
// This client is written against the REAL shipped response
// (TrustScoreQueryResponse), not the stale contract file — flagged here for
// the Tech Lead the same way T027/UserServiceClient's own comments flag a
// contract-vs-implementation gap, and worth a contracts/openapi update in a
// future ticket (see /validate-contracts).
@Component
@Slf4j
@RequiredArgsConstructor
public class RatingServiceClientImpl implements RatingServiceClient {

    // Explicit @Qualifier — see UserServiceClientImpl's field comment for why
    // this is preferred over relying on field-name-to-bean-name matching
    // across WebClientConfig's three same-typed beans.
    @Qualifier("ratingServiceWebClient")
    private final WebClient ratingServiceWebClient;
    private final TrustScoreSnapshotMapper trustScoreSnapshotMapper;

    @Override
    @CircuitBreaker(name = "ratingService", fallbackMethod = "getTrustScoreFallback")
    public TrustScoreSnapshot getTrustScore(String userId) {
        TrustScoreQueryResponseDto response = ratingServiceWebClient.get()
                .uri("/trust/{userId}", userId)
                .retrieve()
                .bodyToMono(TrustScoreQueryResponseDto.class)
                .block();

        if (response == null) {
            // Rating Service's real controller has no 404 branch for this
            // endpoint (TrustScoreReadService always returns SOME result for
            // an existing numeric id) — a null body here is a contract
            // violation, not a legitimate "doesn't exist" outcome, so this
            // fails the same way UserServiceClientImpl.setStatus's missing
            // body does.
            throw new IllegalStateException("Rating Service returned no body for GET /trust/" + userId);
        }

        return trustScoreSnapshotMapper.toTrustScoreSnapshot(response);
    }

    private TrustScoreSnapshot getTrustScoreFallback(String userId, Throwable ex) {
        log.warn("Rating Service trust score unavailable for user {} ({})", userId, ex.toString());
        throw new UpstreamUnavailableException("Rating Service", ex);
    }

    // TODO: Rating Service has no PATCH /trust/:userId yet — stubbed until
    // that ticket ships. Tech Lead decision (T058, 2026-10-24): rather than
    // calling a path that would 404 on every invocation (same failure mode
    // UserServiceClient.getSuspendedUsers' own comment documents for a
    // genuinely-missing upstream endpoint), this method short-circuits with
    // a locally-constructed result that simply echoes the admin's requested
    // score/reason back. No WebClient call, no @CircuitBreaker — there is
    // nothing upstream to fail against yet, so wrapping this in Resilience4j
    // would be decoration around a call that never happens.
    //
    // A future ticket that ships the real Rating Service endpoint deletes
    // this method body and replaces it with the same
    // ratingServiceWebClient.patch()... shape getTrustScore above uses for
    // GET — this TODO text is the marker that ticket searches for.
    @Override
    public TrustScoreOverrideResult overrideTrustScore(String userId, TrustScoreOverrideRequest request) {
        log.warn("Rating Service PATCH /trust/{} is stubbed (T058) — echoing override locally, "
                + "no real upstream call made: score={}, reason={}", userId, request.score(), request.reason());
        return new TrustScoreOverrideResult(userId, request.score(), request.reason());
    }

    // Matches TrustScoreController/TrustScoreQueryResponse's REAL field names
    // exactly (see this class's header comment) — userId is Long on the wire
    // (Rating Service's own ids are numeric Longs, same convention
    // discovery-service's UserServiceClientImpl documents), converted to
    // String here only at this client's own outward boundary
    // (TrustScoreSnapshot) to match every other id in admin-service's own DTOs.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrustScoreQueryResponseDto(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("trust_score") BigDecimal trustScore,
            @JsonProperty("request_limit") int requestLimit,
            @JsonProperty("is_trusted") boolean isTrusted,
            @JsonProperty("rating_count") int ratingCount) {
    }
}
