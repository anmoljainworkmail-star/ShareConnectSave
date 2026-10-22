package com.shareconnectsave.report_service.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shareconnectsave.report_service.model.ReportReason;
import com.shareconnectsave.report_service.model.ReportStatus;

// Matches contracts/openapi/report-service.yaml's Report schema exactly.
// Field names mirror Report's own property names (id, reporterId,
// reportedId, reason, connectionId, details, status, createdAt) on purpose —
// ReportMapper below relies on MapStruct's implicit same-name mapping and
// needs no @Mapping overrides, same convention as connection-service's
// ConnectionResponse/ConnectionMapper pair.
//
// report_count_for_target and escalation state are deliberately NOT part of
// this record — per the contract's own comment, those are internal saga
// bookkeeping consumed off the report.filed Kafka event by Admin Service,
// not client-facing data (Single Responsibility: this DTO describes the
// report, not the saga's state).
public record ReportResponse(
        String id,
        @JsonProperty("reporter_id") String reporterId,
        @JsonProperty("reported_id") String reportedId,
        ReportReason reason,
        @JsonProperty("connection_id") String connectionId,
        String details,
        ReportStatus status,
        @JsonProperty("created_at") Instant createdAt
) {
}
