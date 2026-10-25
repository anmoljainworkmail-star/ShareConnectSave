package com.shareconnectsave.admin_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

// Pattern: Circuit Breaker (Decorator under the hood, per this project's
// CLAUDE.md) — @CircuitBreaker wraps each method transparently, tripping
// open after the "userService" instance's configured failure rate so a
// slow/down User Service can no longer block every admin lookup behind it.
// Same shared "userService" instance/sliding-window discovery-service's own
// UserServiceClientImpl uses.
//
// Different fallback shapes across the methods of the SAME circuit breaker
// instance — deliberate, same "one exception, the call site decides what it
// means" discipline discovery-service's T027 established:
//   - getSuspendedUsers: NO fallback. This is GET /admin/queue's entire data
//     source (see UserServiceClient's own KNOWN GAP comment) — if User
//     Service cannot be reached there is no queue to approximate, so the
//     exception propagates and the whole endpoint 500s. Silently returning
//     an empty list here would be worse: an admin reading an empty queue has
//     no way to tell "nobody is suspended" from "User Service is down and
//     this screen is lying".
//   - getUser: HAS a fallback. This is one of THREE calls GET
//     /admin/users/{id} fans out to (see ReviewQueueServiceImpl) — the
//     ticket's own "Partial-failure degradation" spec requires a single
//     downstream outage to degrade ONE section of that aggregated view, not
//     hide the whole account from review.
//   - setStatus: NO fallback. Reinstate's own spec: "Call
//     UserServiceClient.setStatus(id, 'active') — synchronous HTTP call,
//     User Service is the system of record for status." A WRITE must never
//     silently pretend to succeed — if this throws, ReviewQueueServiceImpl
//     never reaches the outbox write at all, and the whole reinstate request
//     fails (propagates to GlobalExceptionHandler as a 500). Retrying the
//     whole reinstate action afterward is always safe: calling setStatus
//     twice with the same target status is naturally idempotent, so there is
//     no compensating "un-reinstate" call for this class to make.
//   - getStats (T105): NO fallback, same reasoning as getSuspendedUsers — it
//     is the Admin Dashboard platform-health tile's entire data source, so a
//     fabricated zero would misinform an admin rather than visibly fail.
@Component
@Slf4j
@RequiredArgsConstructor
public class UserServiceClientImpl implements UserServiceClient {

    // Explicit @Qualifier, not field-name matching: with three WebClient
    // beans in the container, Spring only resolves by matching the field
    // name to the bean name when it falls back from by-type lookup — so a
    // rename of either side would silently break wiring (or fail loudly with
    // NoUniqueBeanDefinitionException) with no compile-time signal.
    // @Qualifier pins the bean by its name explicitly, independent of
    // whatever this field happens to be called.
    @Qualifier("userServiceWebClient")
    private final WebClient userServiceWebClient;
    private final UserProfileMapper userProfileMapper;

    @Override
    @CircuitBreaker(name = "userService")
    public List<SuspendedUserSummary> getSuspendedUsers() {
        SuspendedUserDto[] suspended = userServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/users/internal").queryParam("accountStatus", "suspended").build())
                .retrieve()
                .bodyToMono(SuspendedUserDto[].class)
                // Virtual Threads (spring.threads.virtual.enabled: true) make
                // .block() safe here — same reasoning as every WebClient call
                // in this codebase since discovery-service's T023.
                .block();

        if (suspended == null) {
            return List.of();
        }

        return List.of(suspended).stream()
                .map(userProfileMapper::toSuspendedUserSummary)
                .toList();
    }

    @Override
    @CircuitBreaker(name = "userService", fallbackMethod = "getUserFallback")
    public UserProfileSummary getUser(String userId) {
        UserProfileDto profile = userServiceWebClient.get()
                .uri("/users/internal/{id}", userId)
                .retrieve()
                // A genuine 404 is a normal, non-exceptional outcome (the
                // user id truly doesn't exist) — turned into an empty Mono so
                // .block() returns null as a plain return value, never an
                // exception Resilience4j would count against this breaker.
                // Same "business outcome vs infrastructure failure" split as
                // discovery-service's getUserCard.
                .onStatus(HttpStatusCode::is4xxClientError, response -> Mono.empty())
                .bodyToMono(UserProfileDto.class)
                .block();

        if (profile == null) {
            return null;
        }

        return userProfileMapper.toUserProfileSummary(profile);
    }

    @Override
    @CircuitBreaker(name = "userService")
    public UserProfileSummary setStatus(String userId, String accountStatus) {
        UserProfileDto updated = userServiceWebClient.put()
                .uri("/users/internal/{id}/account-status", userId)
                .bodyValue(new AccountStatusUpdateRequest(accountStatus))
                .retrieve()
                .bodyToMono(UserProfileDto.class)
                .block();

        if (updated == null) {
            // A 2xx response with no body is a User Service contract
            // violation, not a "missing" business outcome (unlike getUser's
            // 404 case above) — fail loudly rather than let
            // ReviewQueueServiceImpl silently treat a null as "nothing to
            // publish" and skip the outbox write.
            throw new IllegalStateException("User Service returned no body for setStatus(" + userId + ", " + accountStatus + ")");
        }

        return userProfileMapper.toUserProfileSummary(updated);
    }

    // T105: NO fallback — same reasoning as getSuspendedUsers above, not
    // getUser. This call IS the Admin Dashboard platform-health tile's
    // entire data source (there is no partial result to degrade into, unlike
    // getUser's one-of-three-fan-out-calls situation); silently returning a
    // zero or stale count here would let the tile lie to an admin ("0 active
    // users") instead of visibly failing when User Service is unreachable.
    @Override
    @CircuitBreaker(name = "userService")
    public PlatformStats getStats() {
        StatsDto stats = userServiceWebClient.get()
                .uri("/users/internal/stats")
                .retrieve()
                .bodyToMono(StatsDto.class)
                .block();

        if (stats == null) {
            // Same "2xx with no body is a contract violation" reasoning as
            // setStatus's null check above — fail loudly rather than let a
            // caller treat a null/zero as a legitimate "nobody is active".
            throw new IllegalStateException("User Service returned no body for getStats()");
        }

        return new PlatformStats(stats.totalActiveUsers());
    }

    // Re-throws as UpstreamUnavailableException rather than returning a
    // placeholder directly — see that class's own comment for why this must
    // stay distinguishable from getUser's plain "null means a genuine 404"
    // contract. ReviewQueueServiceImpl is the one place that turns THIS
    // exception into the degraded, flagged placeholder the ticket's
    // "Partial-failure degradation" spec describes.
    private UserProfileSummary getUserFallback(String userId, Throwable ex) {
        log.warn("User Service profile unavailable for user {} ({}); degrading in ReviewQueueServiceImpl",
                userId, ex.toString());
        throw new UpstreamUnavailableException("User Service", ex);
    }

    // Nested, package-private wire DTOs — User Service's own JSON shape,
    // deliberately distinct from this client's outward UserProfileSummary/
    // SuspendedUserSummary records (see UserServiceClient's class comment).
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuspendedUserDto(
            @JsonProperty("user_id") String userId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("suspended_at") Instant suspendedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserProfileDto(
            @JsonProperty("user_id") String userId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("account_status") String accountStatus,
            @JsonProperty("suspended_at") Instant suspendedAt) {
    }

    record AccountStatusUpdateRequest(@JsonProperty("account_status") String accountStatus) {
    }

    // T105: User Service's own wire shape for GET /users/internal/stats,
    // deliberately distinct from this client's outward PlatformStats record
    // — same bidirectional-contract-isolation reasoning as
    // SuspendedUserDto/UserProfileDto above.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatsDto(@JsonProperty("total_active_users") long totalActiveUsers) {
    }
}
