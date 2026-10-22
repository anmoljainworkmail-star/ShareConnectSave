package com.shareconnectsave.report_service.service;

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
}
