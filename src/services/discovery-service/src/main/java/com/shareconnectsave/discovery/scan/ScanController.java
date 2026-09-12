package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanLocationRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartResponse;
import com.shareconnectsave.shared.ErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Pattern: thin controller — every method only translates HTTP in/out and
// delegates to ScanSessionService, mirroring the thin-controller rule
// already applied in User Service's UserProfileController.
//
// Depends on the ScanSessionService interface, not ScanSessionServiceImpl —
// same Dependency Inversion shape as UserProfileController depending on
// IUserProfileService. Whichever @Service bean Spring wires in behind the
// interface, this class never has to change.
//
// JWT Identity: X-User-Id is read as a plain Long header, already validated
// and injected by the API Gateway — this controller never decodes a JWT
// itself (same convention as User Service's HttpContextExtensions.TryGetUserId).
@RestController
@RequestMapping("/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanSessionService scanSessionService;

    @PostMapping("/start")
    public ResponseEntity<ScanStartResponse> startScan(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody ScanStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scanSessionService.startScan(userId, request));
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stopScan(@RequestHeader("X-User-Id") Long userId) {
        scanSessionService.stopScan(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/location")
    public ResponseEntity<Void> updateLocation(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody ScanLocationRequest request) {
        scanSessionService.updateLocation(userId, request);
        return ResponseEntity.noContent().build();
    }

    // Scoped to this controller only (not the shared GlobalExceptionHandler)
    // — "no active scan" is a concept specific to the scan feature, so its
    // 404 mapping lives next to the code that raises it, same Single
    // Responsibility reasoning that keeps GlobalExceptionHandler itself
    // generic and shared.
    @ExceptionHandler(ScanSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScanSessionNotFound(ScanSessionNotFoundException ex) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SCAN_SESSION_NOT_FOUND", ex.getMessage(), traceId != null ? traceId : ""));
    }
}
