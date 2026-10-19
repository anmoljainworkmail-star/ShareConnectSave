package com.shareconnectsave.connection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern (same reasoning as discovery-service's UserServiceProperties,
// T023) - kept as its own record, not folded into some wider "connection"
// properties tree, because "rating-service.base-url" is a separate
// application.yml top-level section describing an external dependency's
// address, not one of this service's own tunable thresholds.
//
// No Hardcoded Config (Architecture Rule #3): RatingServiceClientConfig
// injects this bean rather than a @Value("${rating-service.base-url}")
// string, so the Rating Service address is never a literal buried in
// RatingServiceClientImpl.
@ConfigurationProperties(prefix = "rating-service")
public record RatingServiceProperties(String baseUrl) {
}
