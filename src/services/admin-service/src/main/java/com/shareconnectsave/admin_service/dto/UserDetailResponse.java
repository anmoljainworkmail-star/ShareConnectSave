package com.shareconnectsave.admin_service.dto;

import java.util.List;

// GET /admin/users/{id}'s response shape — Facade-like aggregation (see
// ReviewQueueService's own class comment) of exactly three upstream calls.
// Every "...Unavailable" flag below exists for the SAME reason: the ticket's
// "Partial-failure degradation" spec requires a single downstream outage to
// degrade only its own slice of this view, never 500 the whole endpoint and
// never silently render missing data as if it were real (null score, empty
// report list) with no way for an admin to tell the difference.
public record UserDetailResponse(
        String userId,
        String displayName,
        String status,
        boolean profileUnavailable,
        Double trustScore,
        boolean trustScoreUnavailable,
        List<ReportHistoryEntry> reportHistory,
        int reportCount,
        boolean reportHistoryUnavailable) {
}
