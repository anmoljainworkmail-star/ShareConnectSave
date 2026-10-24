package com.shareconnectsave.admin_service.client;

import java.time.Instant;
import java.util.List;

// Dependency Inversion (SOLID-D) + Interface Segregation (SOLID-I). Reads
// only, per this ticket's own "What NOT to do" list: this interface has NO
// method that could ever call or mutate Report Service's GET /reports/queue
// (the ESCALATED-reports-only view T053 built) — that is a deliberately
// separate, narrower concept from this ticket's per-user report history.
public interface ReportServiceClient {

    // KNOWN GAP (same shape as UserServiceClient.getSuspendedUsers' own
    // comment): Report Service's actual ReportController implements only
    // POST /reports and GET /reports/queue today — no endpoint exists yet to
    // fetch one user's report history, despite admin-service.yaml's own
    // GET /admin/users/{id}/reports describing exactly that proxy. This
    // method is written against the natural extension of Report Service's
    // existing (but unfiltered) GET /reports admin-listing endpoint —
    // GET /reports?reportedId={userId} — which also does not exist in
    // contracts/openapi/report-service.yaml yet. Until a future Report
    // Service ticket adds it, every call here throws
    // UpstreamUnavailableException, and ReviewQueueServiceImpl's degrade path
    // (reportHistoryUnavailable=true) is what actually runs in practice.
    List<ReportHistoryItem> getReportHistory(String reportedUserId);

    record ReportHistoryItem(String id, String reporterId, String reason, String status, Instant createdAt) {
    }
}
