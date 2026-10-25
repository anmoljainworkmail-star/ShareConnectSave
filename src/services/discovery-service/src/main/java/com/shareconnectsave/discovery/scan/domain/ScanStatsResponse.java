package com.shareconnectsave.discovery.scan.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// T106 — Admin Dashboard platform-health tile, same shape as
// ScanStartResponse's own session_id convention: the wire contract
// (total_scans_today, snake_case) is spelled out explicitly here rather than
// left to Jackson's default camelCase, so this response stays byte-for-byte
// what the ticket's acceptance criterion demands even if the Java field name
// ever changes. Deliberately just one field, no wrapper — the ticket's own
// "What NOT to do" list is explicit that this DTO carries nothing else.
public record ScanStatsResponse(
        @JsonProperty("total_scans_today") long totalScansToday
) {
}
