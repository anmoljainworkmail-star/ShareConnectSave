package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern — see UserServiceProperties'/RatingServiceProperties' own
// comments. A single field, unlike UserServiceProperties: Connection
// Service's PUT /admin/connections/users/{userId}/expire-active trusts a
// per-request X-User-Role header alone (set by ConnectionServiceClientImpl,
// not a static header on this bean) — it has no shared-secret
// X-Internal-Service-Key guard the way User Service's /users/internal/...
// endpoints do, so there is no second value for this record to carry.
@ConfigurationProperties(prefix = "connection-service")
public record ConnectionServiceProperties(String baseUrl) {
}
