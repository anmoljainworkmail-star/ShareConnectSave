package com.shareconnectsave.discovery.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

// Pattern: Circuit Breaker (Decorator under the hood, per this project's
// CLAUDE.md) — @CircuitBreaker wraps each public method transparently,
// tripping open after the "userService" instance's configured failure rate
// so a slow/down User Service can no longer block every /scan/nearby
// request behind it. getBlockList still routes straight to its own
// fallback method on trip/failure (fail-open to an empty list, see that
// method); getUserCard deliberately does NOT (see the T027 paragraph
// below) — same annotation, same shared "userService" instance and sliding
// window, two different post-failure shapes. Same shape as the
// java-spring-boot skill's own example.
//
// T026 change: this class no longer touches Redis at all. Before T026 it
// held its own fallback-only profile cache (a RedisTemplate field, populated
// on every live success, read only when the circuit tripped) — that was
// caching logic leaking into what should be a pure upstream HTTP client
// (Single Responsibility, SOLID-S). Cache-Aside orchestration, including what
// to do on a miss OR a failure, now lives entirely in DiscoveryCacheService;
// this class's only job is "call User Service, or fail" — a fallback here
// simply returns null/empty and lets the caller (DiscoveryCacheService)
// decide what a missing answer means.
//
// T027 change: getUserCard's @CircuitBreaker now declares NO fallbackMethod
// at all (getBlockList's below is unchanged — see that method's own
// comment). Resilience4j's annotation still wraps the call for exactly the
// reason it always has — measuring failures against the "userService"
// sliding window and fast-failing with CallNotPermittedException once it
// opens — but with no fallbackMethod configured, that exception (or whatever
// the real WebClient call threw) is simply left to propagate to whoever
// called getUserCard, instead of being swallowed here. That caller is
// DiscoveryCacheService, the one class T026 already made responsible for
// Redis, so it is also the one place that can honor T027's "serve the
// cached profile, else a placeholder" fallback without this class reaching
// back into Redis (which would reintroduce exactly the caching-logic-leak
// T026 just removed) or DiscoveryCacheService duplicating this class's own
// try/catch around the same call. One exception, one place that decides
// what to do about it.
@Component
@Slf4j
@RequiredArgsConstructor
public class UserServiceClientImpl implements UserServiceClient {

    private final WebClient userServiceWebClient;

    // Note there is deliberately no fallbackMethod on this annotation — see
    // the T027 paragraph in the class comment above for why the exception is
    // left to propagate to DiscoveryCacheService rather than being handled
    // here. Also note a genuine 404 never reaches that exception path at
    // all: .onStatus below turns it into an empty Mono, so .block() just
    // returns null as a NORMAL return value — Resilience4j only ever counts
    // (and only ever routes to a fallback) an exception, never a null. That
    // is what keeps "this user id doesn't exist" (a business outcome, still
    // "omit the candidate") distinct from "User Service is unreachable/
    // erroring" (an infrastructure failure, T027's cached-or-placeholder
    // case) — the two would otherwise be indistinguishable null returns.
    @Override
    @CircuitBreaker(name = "userService")
    public UserCardResponse getUserCard(Long userId, double distanceKm) {
        PublicUserProfileResponse profile = userServiceWebClient.get()
                .uri("/users/{id}", userId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> Mono.empty())
                .bodyToMono(PublicUserProfileResponse.class)
                // Virtual Threads (spring.threads.virtual.enabled: true, see
                // application.yml) are what make .block() safe here: this
                // call runs on a cheap JVM-managed virtual thread that
                // unmounts from its OS carrier while blocked on this
                // round-trip, instead of pinning one of a fixed pool of
                // platform threads for the whole call.
                .block();

        if (profile == null) {
            return null;
        }

        return new UserCardResponse(profile.id(), profile.name(), profile.photoUrl(), profile.identityBadge(), distanceKm, false);
    }

    @Override
    @CircuitBreaker(name = "userService", fallbackMethod = "getBlockListFallback")
    public List<Long> getBlockList(Long userId) {
        BlockedUserDto[] blocked = userServiceWebClient.get()
                .uri("/blocks")
                // Trusted internal service-to-service header: Discovery sets
                // this itself for whichever user's block list it needs
                // (caller or candidate) — this call never crosses the API
                // Gateway, so there is no inbound JWT here to derive it from;
                // it is this service asserting an identity on an internal,
                // Docker-network-only call, not a client-facing endpoint.
                .header("X-User-Id", String.valueOf(userId))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> Mono.empty())
                .bodyToMono(BlockedUserDto[].class)
                .block();

        return blocked != null
                ? List.of(blocked).stream().map(BlockedUserDto::userId).toList()
                : List.of();
    }

    // Known limitation (T023 tech-lead research notes): User Service's
    // actual C# controllers have no /blocks implementation yet —
    // contracts/openapi/user-service.yaml defines the contract, but no code
    // implements it (a real, pre-existing gap in the 96-task plan, not a bug
    // introduced by this ticket). Every call above currently 404s or
    // connection-refuses, tripping this fallback on essentially every
    // invocation until User Service ships that endpoint.
    //
    // Fail-open (empty list) is the deliberate choice: block-list data being
    // unavailable must never fail the whole /scan/nearby request, and
    // wrongly treating two users as blocked because a dependency happened to
    // be down is a worse failure than the reverse (briefly showing a match
    // that a real block list would have excluded).
    private List<Long> getBlockListFallback(Long userId, Throwable ex) {
        log.warn("Block list unavailable for user {} ({}); degrading to empty list (fail-open)",
                userId, ex.toString());
        return List.of();
    }

    // Nested, package-private — this is the exact wire shape User Service
    // returns from GET /users/{id} and used nowhere outside this class.
    // UserCardResponse (Discovery's own outward-facing DTO) is deliberately a
    // DIFFERENT type: User Service's field set can evolve without this
    // service's own public API contract having to move in lockstep with it.
    record PublicUserProfileResponse(
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("photo_url") String photoUrl,
            @JsonProperty("identity_badge") boolean identityBadge) {
    }

    // contracts/openapi/user-service.yaml still types user_id as a uuid
    // string — a leftover from before this project pivoted every service to
    // Long ids (same drift ScanController's own header comment already
    // flags for X-User-Id). Deserialized here as Long to match what every
    // OTHER service in this codebase actually does today, not what the
    // stale contract file says.
    record BlockedUserDto(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("blocked_at") Instant blockedAt) {
    }
}
