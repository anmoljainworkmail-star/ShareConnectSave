package com.shareconnectsave.admin_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

// Pattern: Circuit Breaker (Decorator under the hood, per this project's
// CLAUDE.md) — same framing as UserServiceClientImpl's/RatingServiceClientImpl's
// own class comments. Wraps admin-service's FIRST-EVER call into Discovery
// Service under a new named instance, "discoveryService" (see application.yml),
// same failure-rate/sliding-window shape as every other upstream instance
// this service already configures.
//
// NO fallback method on getStats() — same reasoning as
// UserServiceClientImpl.getStats() (T105): this call IS the Admin Dashboard's
// "scans started today" tile's entire data source, not one of several
// fan-out calls with a partial result to degrade into. If Discovery Service
// is unreachable, the exception propagates uncaught rather than this client
// returning a fabricated 0 that would misinform an admin into thinking
// platform activity has genuinely dropped to zero.
@Component
@Slf4j
@RequiredArgsConstructor
public class DiscoveryServiceClientImpl implements DiscoveryServiceClient {

    private static final String ADMIN_ROLE = "admin";

    // Explicit @Qualifier — same reasoning as every other client field in
    // this service: with multiple WebClient beans in the container, a
    // rename on either side would otherwise fail silently or loudly with no
    // compile-time signal.
    @Qualifier("discoveryServiceWebClient")
    private final WebClient discoveryServiceWebClient;

    @Override
    @CircuitBreaker(name = "discoveryService")
    public PlatformScanStats getStats() {
        // X-User-Role sent per-call, not a default header on the WebClient
        // bean — same reasoning as ConnectionServiceClientImpl's own field
        // comment: this is the one piece of identity Discovery Service's
        // @PreAuthorize("hasRole('ADMIN')") check actually evaluates (via
        // DiscoveryAdminRoleAuthenticationFilter populating the
        // SecurityContext), and keeping it explicit here, next to the one
        // call site that needs it, is more readable than hiding it in
        // WebClientConfig for a client with exactly one method.
        ScanStatsDto response = discoveryServiceWebClient.get()
                .uri("/admin/scan/stats")
                .header("X-User-Role", ADMIN_ROLE)
                .retrieve()
                .bodyToMono(ScanStatsDto.class)
                // Virtual Threads (spring.threads.virtual.enabled: true) make
                // .block() safe here — same reasoning as every other
                // WebClient call in this codebase since discovery-service's
                // own T023.
                .block();

        if (response == null) {
            // A 2xx response with no body is a Discovery Service contract
            // violation, not a legitimate "zero scans" outcome (that case
            // still returns a body, { "total_scans_today": 0 }) — same
            // "fail loudly on a missing body" discipline as
            // UserServiceClientImpl.getStats().
            throw new IllegalStateException("Discovery Service returned no body for getStats()");
        }

        return new PlatformScanStats(response.totalScansToday());
    }

    // Nested, package-private wire DTO matching Discovery Service's own
    // ScanStatsResponse shape exactly (snake_case total_scans_today) — same
    // bidirectional-contract-isolation reasoning as UserServiceClientImpl's
    // own StatsDto.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScanStatsDto(@JsonProperty("total_scans_today") long totalScansToday) {
    }
}
