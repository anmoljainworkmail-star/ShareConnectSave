package com.shareconnectsave.report_service.service;

import java.util.List;

import com.shareconnectsave.report_service.dto.ReportCreateDto;
import com.shareconnectsave.report_service.dto.ReportResponse;

// Dependency Inversion (SOLID-D): ReportController depends on THIS
// interface, never on ReportServiceImpl directly — same shape as every
// other XxxService/XxxServiceImpl pair in this platform (ConnectionService/
// ConnectionServiceImpl, RatingTrustScoreService/Impl, ...), so a future
// test (or an alternate implementation) can substitute without touching the
// controller.
public interface ReportService {

    ReportResponse fileReport(String reporterId, ReportCreateDto dto);

    // T053 — backs GET /reports/queue. The admin-role guard clause itself
    // lives at the controller boundary (ReportController), not here: this
    // method answers "what is the escalation queue", not "who is allowed to
    // ask" — Single Responsibility (SOLID-S), same split this ticket keeps
    // between escalation decision logic and authorization.
    List<ReportResponse> getEscalatedQueue();
}
