package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern — same convention as ConnectionServiceProperties'/
// DiscoveryServiceProperties' own comments. Binds
// admin.dashboard.cache-ttl-seconds from application.yml into a typed,
// immutable record instead of a hardcoded literal 60 inside
// CacheConfig/DashboardStatsServiceImpl (Architecture Rule #3, No Hardcoded
// Config). Auto-registered as a bean by AdminServiceApplication's existing
// @ConfigurationPropertiesScan — no new annotation needed there.
@ConfigurationProperties(prefix = "admin.dashboard")
public record DashboardProperties(long cacheTtlSeconds) {
}
