package com.shareconnectsave.admin_service.dashboard;

import java.util.List;

// GET /admin/stats's response shape. Plain camelCase field names, no
// @JsonProperty — matching this service's OWN existing outward contracts
// (ReviewQueueItem, SuspendResponse), not T105/T107's snake_case convention:
// that convention belongs to THOSE services' own stats-tile wire shapes
// (User/Connection Service's own outward contracts), which this class's
// client DTOs (UserServiceClientImpl.StatsDto etc.) already bridge away from.
// DashboardStatsResponse is admin-service's own outward contract, so it
// follows admin-service's own house style.
//
// Every numeric metric is paired with its own "Unavailable" boolean — same
// shape UserDetailResponse already uses for trustScoreUnavailable — rather
// than a null/zero a caller could mistake for a genuine zero count (this
// ticket's own "never fabricate a 0" rule). topReportedUsers mirrors that
// same discipline with an empty list instead of null, matching
// UserDetailResponse.reportHistory's own convention.
public record DashboardStatsResponse(
        Long totalActiveUsers,
        boolean totalActiveUsersUnavailable,
        Long totalScansToday,
        boolean totalScansTodayUnavailable,
        Integer totalConnectionsAcceptedToday,
        boolean totalConnectionsAcceptedTodayUnavailable,
        Integer totalReportsToday,
        boolean totalReportsTodayUnavailable,
        List<TopReportedUserSummary> topReportedUsers,
        boolean topReportedUsersUnavailable) {

    // Nested, admin-service-owned shape — deliberately not a reuse of
    // ReportServiceClient.TopReportedUser across the client/outward-contract
    // boundary (same "no outward-facing contract drift" reasoning every
    // other client-vs-DTO pair in this service already follows): Report
    // Service's wire shape can evolve without this response moving in
    // lockstep with it.
    public record TopReportedUserSummary(String reportedId, long reportCount) {
    }
}
