package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern (same reasoning as discovery-service's own
// UserServiceProperties): "user-service.base-url" rides in as a normal bean
// parameter, not a scattered @Value("${user-service.base-url}") string
// repeated anywhere a base URL is needed.
@ConfigurationProperties(prefix = "user-service")
public record UserServiceProperties(String baseUrl) {
}
