package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern — see UserServiceProperties' own comment. A separate record
// (not one shared "upstream-services" properties class with three fields)
// because each prefix is its own independent application.yml top-level
// section, same granularity discovery-service already established.
@ConfigurationProperties(prefix = "rating-service")
public record RatingServiceProperties(String baseUrl) {
}
