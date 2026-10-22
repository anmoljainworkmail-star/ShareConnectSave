package com.shareconnectsave.report_service.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shareconnectsave.report_service.dto.ReportCreateDto;
import com.shareconnectsave.report_service.dto.ReportResponse;
import com.shareconnectsave.report_service.exception.NotAdminException;
import com.shareconnectsave.report_service.service.ReportService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Pattern: thin controller — this method only translates HTTP in/out and
// delegates to ReportService, matching the project-wide "every controller
// delegates to a service layer" convention (ConnectionController,
// discovery-service's ScanController).
//
// Depends on the ReportService interface, not ReportServiceImpl —
// Dependency Inversion (SOLID-D).
//
// JWT Identity / API Gateway (trust boundary): X-User-Id is read as a plain
// String header, already validated and injected by YARP at the gateway —
// this controller never decodes a JWT itself.
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public ResponseEntity<ReportResponse> fileReport(
            @RequestHeader("X-User-Id") String reporterId,
            @Valid @RequestBody ReportCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.fileReport(reporterId, dto));
    }

    // T053 — admin review queue. No Spring Security filter chain exists in
    // this service (T056 builds one, but only for admin-service, in a later
    // phase) — so the admin check is a plain header read plus a guard
    // clause, same shape every other domain rule in this service already
    // uses (SelfReportException, InvalidReportReasonException), rather than
    // inventing an authorization framework just for this one endpoint.
    @GetMapping("/queue")
    public ResponseEntity<List<ReportResponse>> getEscalatedQueue(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") String role) {
        // Guard clause over nested conditionals: reject before ever calling
        // into ReportService, so the service layer never has to know
        // authorization exists at all (Single Responsibility, SOLID-S).
        if (!"admin".equals(role)) {
            throw new NotAdminException(userId);
        }
        return ResponseEntity.ok(reportService.getEscalatedQueue());
    }
}
