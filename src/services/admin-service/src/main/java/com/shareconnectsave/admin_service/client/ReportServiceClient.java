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

    // T108 — Admin Dashboard "top reported users" tile. Unlike
    // getReportHistory above, this calls an endpoint that genuinely exists
    // (GET /reports/top-reported, T108) — no KNOWN GAP here.
    //
    // Distinct Query Intent, same reasoning T108's report-service ticket
    // applies server-side: this is NOT a client for GET /reports/queue's
    // escalated-only view (ReportServiceClient deliberately has no method
    // for that, per this interface's own class comment) — it is a live,
    // fresh-every-call aggregate over the last `days` days across every
    // report status, so admin-service must never cache its result either.
    List<TopReportedUser> getTopReported(int days, int limit);

    record TopReportedUser(String reportedId, long reportCount) {
    }

    // T060 — Admin Dashboard "total reports today" tile. KNOWN GAP, same
    // shape as getReportHistory's own class comment above: Report Service's
    // actual ReportController implements only POST /reports,
    // GET /reports/queue (escalated-only) and GET /reports/top-reported
    // today — no plain, unfiltered admin-listing endpoint exists yet. This
    // method is written against the same not-yet-shipped GET /reports
    // (no query params, admin-only) that getReportHistory's own comment
    // already assumes for its reportedId-filtered variant; SPECS.md's own
    // Phase 17 audit judged that gap "good enough" to leave unfilled rather
    // than fund a fifth T105-T108-style companion ticket for one metric (see
    // this ticket's own text). Per that same explicit scope decision,
    // admin-service does NOT grow a matching endpoint on Report Service
    // itself — until a future Report Service ticket adds GET /reports, this
    // call fails every time, and DashboardStatsServiceImpl's degrade path
    // (totalReportsTodayUnavailable=true) is what actually runs in practice,
    // the same way getUserDetail's reportHistoryUnavailable path already
    // covers getReportHistory's identical gap.
    //
    // Returns the already-computed COUNT, not the raw list — Interface
    // Segregation (SOLID-I), same reasoning ConnectionServiceClient.getStats()
    // documents for returning a plain int instead of a List for the caller
    // to re-derive: DashboardStatsServiceImpl needs exactly one number, never
    // the full report rows.
    int getReportsToday();
}
