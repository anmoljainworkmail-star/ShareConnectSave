package com.shareconnectsave.admin_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern — see UserServiceProperties' own comment.
@ConfigurationProperties(prefix = "report-service")
public record ReportServiceProperties(String baseUrl) {
}
