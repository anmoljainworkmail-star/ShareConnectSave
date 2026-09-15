package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanLocationRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartResponse;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import com.shareconnectsave.shared.ErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final ScanQueryService scanQueryService;

    @PostMapping("/start")
    public ResponseEntity<ScanStartResponse> startScan(
            @RequestHeader("X-User-Id") Long userId,
            // Required, same as X-User-Id: the gateway's JwtValidationMiddleware
            // refuses to forward a request unless the JWT's "gender" claim is
            // present (see api-gateway/Middleware/JwtValidationMiddleware.cs),
            // and User.Gender defaults to "Unspecified" rather than being
            // absent (user-service/Models/User.cs) — so this header is never
            // missing, it can just legitimately carry the value "Unspecified".
            // Trusted (gateway-injected, JWT Identity rule) — never taken from
            // the request body, which is why ScanStartRequest.womenOnly() alone
            // can't decide anything on its own (see ScanSessionServiceImpl.startScan).
            @RequestHeader("X-User-Gender") String gender,
            @Valid @RequestBody ScanStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scanSessionService.startScan(userId, gender, request));
    }

    // No X-User-Gender parameter here: the women-only predicate this ticket
    // implements reads the ALREADY-RESOLVED scan:{session_id}:women_only value
    // ScanSessionServiceImpl wrote at /scan/start (see ScanQueryServiceImpl),
    // not a header re-sent on every single query call — so this endpoint has
    // no use for it.
    @GetMapping("/nearby")
    public ResponseEntity<List<UserCardResponse>> getNearby(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(scanQueryService.findNearby(userId));
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

    // 403, not 404: the caller is authenticated and does exist, they are
    // simply not (yet, or no longer) allowed to participate in discovery —
    // that is an authorization outcome, not a missing-resource one.
    @ExceptionHandler(UserNotEligibleForDiscoveryException.class)
    public ResponseEntity<ErrorResponse> handleUserNotEligible(UserNotEligibleForDiscoveryException ex) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("USER_NOT_ELIGIBLE_FOR_DISCOVERY", ex.getMessage(), traceId != null ? traceId : ""));
    }
}
