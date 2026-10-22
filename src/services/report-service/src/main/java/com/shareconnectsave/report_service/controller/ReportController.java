package com.shareconnectsave.report_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shareconnectsave.report_service.dto.ReportCreateDto;
import com.shareconnectsave.report_service.dto.ReportResponse;
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
}
