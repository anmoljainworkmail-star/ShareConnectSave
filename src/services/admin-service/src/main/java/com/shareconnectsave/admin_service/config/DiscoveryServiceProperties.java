package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern — see UserServiceProperties'/ConnectionServiceProperties'
// own comments. A single field, same as ConnectionServiceProperties: this
// client authorizes itself with a per-request X-User-Role header
// (DiscoveryServiceClientImpl sets it explicitly at the one call site), not
// a shared-secret X-Internal-Service-Key default header the way
// UserServiceProperties carries — Discovery Service's new GET
// /admin/scan/stats endpoint is guarded by Spring Security method security
// reading that same header, not an internal-service-key check, so there is
// no second value for this record to carry.
@ConfigurationProperties(prefix = "discovery-service")
public record DiscoveryServiceProperties(String baseUrl) {
}
