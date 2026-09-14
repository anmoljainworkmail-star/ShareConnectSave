package com.shareconnectsave.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Same Options-pattern reasoning as DiscoveryProperties (see that class's
// comment) — kept as its own record rather than folded in there because
// "user-service.base-url" is a separate application.yml top-level section:
// it describes an external dependency's address, not one of this service's
// own tunable thresholds.
@ConfigurationProperties(prefix = "user-service")
public record UserServiceProperties(String baseUrl) {
}
