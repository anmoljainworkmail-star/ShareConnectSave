package com.shareconnectsave.report_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// One row of GET /reports/top-reported's response. Field names mirror the
// AGGREGATION PIPELINE's own final output field names (reportedId,
// reportCount) in ReportServiceImpl.getTopReportedUsers — not Report's own
// property names — because this DTO shapes a computed aggregate, not a
// stored document, the same "DTO describes what the client needs, not what
// is in the database" split ReportResponse's own class comment already
// draws for report_count_for_target/escalation state.
//
// @JsonProperty per field, matching ReportResponse's own convention in this
// package (this service has no global Jackson snake_case naming strategy
// configured in application.yml — camelCase-to-snake_case is spelled out
// explicitly, field by field, everywhere in report-service).
public record TopReportedUserDto(
        @JsonProperty("reported_id") String reportedId,
        @JsonProperty("report_count") long reportCount
) {
}
