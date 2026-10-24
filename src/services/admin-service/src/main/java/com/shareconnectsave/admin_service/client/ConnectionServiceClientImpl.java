package com.shareconnectsave.admin_service.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

// Pattern: Circuit Breaker (Decorator under the hood, per this project's
// CLAUDE.md) — same framing as UserServiceClientImpl's own class comment.
// Wraps admin-service's FIRST-EVER call into Connection Service with the
// same failure-rate/sliding-window shape as the userService/ratingService/
// reportService instances (see application.yml's "one named instance per
// upstream service" comment) under a fourth instance, "connectionService".
//
// NO fallback value on this method — same reasoning as
// UserServiceClientImpl.setStatus: this is a WRITE (suspend's step 2, per
// T059's own ordering spec), and a write must never silently pretend to
// succeed by returning a fabricated expired count. The fallback below
// re-throws the SAME shared client.UpstreamUnavailableException every other
// client in this service already uses (see that class's own Single
// Responsibility comment) rather than inventing a Connection-Service-
// specific exception at THIS layer — ReviewQueueServiceImpl.suspend is the
// one place that decides what "Connection Service is unavailable" means for
// this particular orchestration step (wrapping it into
// ConnectionServiceUnavailableException), the exact same split reinstate()
// already establishes around RatingServiceClient.getTrustScore /
// UpstreamUnavailableForWriteException.
@Component
@Slf4j
@RequiredArgsConstructor
public class ConnectionServiceClientImpl implements ConnectionServiceClient {

    private static final String ADMIN_ROLE = "admin";

    // Explicit @Qualifier — same reasoning as UserServiceClientImpl's own
    // field comment: with multiple WebClient beans in the container, a
    // rename on either side would otherwise fail silently or loudly with no
    // compile-time signal.
    @Qualifier("connectionServiceWebClient")
    private final WebClient connectionServiceWebClient;

    @Override
    @CircuitBreaker(name = "connectionService", fallbackMethod = "expireActiveConnectionsFallback")
    public int expireActiveConnections(String userId) {
        // X-User-Role sent per-call, not a default header on the WebClient
        // bean (unlike X-Internal-Service-Key on userServiceWebClient) —
        // this is the one piece of identity T104's AdminConnectionController
        // actually checks (see its own guard-clause comment), and keeping it
        // explicit here, next to the one call site that needs it, is more
        // readable than hiding it in WebClientConfig for a client with
        // exactly one method.
        ExpireActiveConnectionsResponseDto response = connectionServiceWebClient.put()
                .uri("/admin/connections/users/{userId}/expire-active", userId)
                .header("X-User-Role", ADMIN_ROLE)
                .retrieve()
                .bodyToMono(ExpireActiveConnectionsResponseDto.class)
                // Virtual Threads (spring.threads.virtual.enabled: true) make
                // .block() safe here — same reasoning as every other
                // WebClient call in this codebase since discovery-service's
                // T023 (see UserServiceClientImpl.getSuspendedUsers's own
                // comment).
                .block();

        if (response == null) {
            // A 2xx response with no body is a Connection Service contract
            // violation, not a legitimate "zero expired" outcome (that case
            // still returns a body, { "expiredCount": 0 }) — same
            // "fail loudly on a missing body" discipline as
            // UserServiceClientImpl.setStatus.
            throw new IllegalStateException(
                    "Connection Service returned no body for expireActiveConnections(" + userId + ")");
        }

        return response.expiredCount();
    }

    // Re-throws as UpstreamUnavailableException rather than returning a
    // placeholder count directly — see that class's own comment for why a
    // write-path failure must stay distinguishable and never collapse into a
    // silent "0 expired". ReviewQueueServiceImpl.suspend is the one place
    // that turns THIS exception into ConnectionServiceUnavailableException.
    private int expireActiveConnectionsFallback(String userId, Throwable ex) {
        log.warn("Connection Service unavailable while expiring active connections for user {} ({})",
                userId, ex.toString());
        throw new UpstreamUnavailableException("Connection Service", ex);
    }

    // Nested, package-private wire DTO matching Connection Service's own
    // ExpireActiveConnectionsResponse shape exactly: plain camelCase,
    // no @JsonProperty — see that record's own comment for why it
    // deliberately isn't snake_case like this service's other upstream wire
    // DTOs (UserServiceClientImpl's SuspendedUserDto/UserProfileDto).
    record ExpireActiveConnectionsResponseDto(int expiredCount) {
    }
}
