package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern (same reasoning as discovery-service's own
// UserServiceProperties): "user-service.base-url" rides in as a normal bean
// parameter, not a scattered @Value("${user-service.base-url}") string
// repeated anywhere a base URL is needed.
//
// T102: internalServiceKey is the shared secret User Service's
// GET/PUT /users/internal/... endpoints require on every call (same
// Trust Boundary / Defense in Depth guard as its existing
// GET /users/internal/{id}/fcm-token) — see
// UserServiceClientImpl.getSuspendedUsers/getUser/setStatus, which are the
// first calls admin-service ever makes to a User Service internal-only
// endpoint, and WebClientConfig.userServiceWebClient, which attaches it as a
// default header on every request this client sends. Unlike baseUrl, this
// value must match the SAME secret User Service itself is configured with
// (its own InternalServiceKey) — two services agreeing on one shared value,
// not each picking their own.
@ConfigurationProperties(prefix = "user-service")
public record UserServiceProperties(String baseUrl, String internalServiceKey) {
}
