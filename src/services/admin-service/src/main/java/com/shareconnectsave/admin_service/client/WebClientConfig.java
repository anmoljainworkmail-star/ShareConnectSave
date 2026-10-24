package com.shareconnectsave.admin_service.client;

import com.shareconnectsave.admin_service.config.ConnectionServiceProperties;
import com.shareconnectsave.admin_service.config.RatingServiceProperties;
import com.shareconnectsave.admin_service.config.ReportServiceProperties;
import com.shareconnectsave.admin_service.config.UserServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// Builder pattern (GoF), plain English: a builder assembles something step by
// step (set the base URL, set a default header, THEN build it) instead of one
// constructor call that takes every option at once. Think of ordering a
// sandwich by naming each ingredient one at a time rather than reciting a
// 12-word code to the cashier — WebClient.Builder works the same way: each
// .baseUrl(...)/.defaultHeader(...) call returns the same builder so they
// chain, and nothing is actually built until .build() runs. Same exact shape
// as discovery-service's UserServiceClientConfig.
//
// Four beans in ONE config class, not four near-identical files (discovery-
// service gave UserServiceClientConfig its own file because it was the only
// outbound client that service had at setup time): admin-service's whole
// reason to exist is aggregating/orchestrating exactly these upstream
// services, so keeping their construction side by side here is more
// readable than four files that would only ever be read together anyway.
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient userServiceWebClient(WebClient.Builder builder, UserServiceProperties userServiceProperties) {
        // T102: Trust Boundary / Defense in Depth — every call this client
        // makes now targets a /users/internal/... endpoint (see
        // UserServiceClientImpl), each of which checks this same shared-secret
        // header before doing anything else (same guard as User Service's own
        // GET /users/internal/{id}/fcm-token). Attached once here as a default
        // header, not re-added at each .get()/.put() call site in
        // UserServiceClientImpl, because EVERY request this WebClient instance
        // sends needs it — there is no public-endpoint call left on this
        // client to accidentally leak it to.
        return builder
                .baseUrl(userServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("X-Internal-Service-Key", userServiceProperties.internalServiceKey())
                .build();
    }

    @Bean
    public WebClient ratingServiceWebClient(WebClient.Builder builder, RatingServiceProperties ratingServiceProperties) {
        return builder
                .baseUrl(ratingServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public WebClient reportServiceWebClient(WebClient.Builder builder, ReportServiceProperties reportServiceProperties) {
        return builder
                .baseUrl(reportServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // T059: admin-service's first-ever outbound call into Connection
    // Service. No default X-Internal-Service-Key-style header here — unlike
    // userServiceWebClient, this client's one call authorizes itself with a
    // per-request X-User-Role header instead (see
    // ConnectionServiceClientImpl's own comment for why that stays at the
    // call site rather than becoming a default header on this bean).
    @Bean
    public WebClient connectionServiceWebClient(WebClient.Builder builder,
            ConnectionServiceProperties connectionServiceProperties) {
        return builder
                .baseUrl(connectionServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
