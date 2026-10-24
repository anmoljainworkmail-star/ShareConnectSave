package com.shareconnectsave.admin_service.exception;

import com.shareconnectsave.shared.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Domain-specific @RestControllerAdvice, living ALONGSIDE (not replacing) the
// shared GlobalExceptionHandler that ExceptionHandlingConfig @Imports — same
// split as report-service's ReportExceptionHandler and rating-service's
// RatingExceptionHandler: GlobalExceptionHandler stays generic and shared,
// this service's own vocabulary lives next to the feature code that raises it.
@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("USER_NOT_FOUND", ex.getMessage(), traceId()));
    }

    // 503, not 500: this is a known, named upstream-dependency outage, not an
    // unexpected programming error — same reasoning that gives
    // CallNotPermittedException (Resilience4j's own open-circuit exception) a
    // specific, documented meaning rather than falling through to a generic
    // "unexpected server error" response.
    @ExceptionHandler(UpstreamUnavailableForWriteException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailableForWrite(UpstreamUnavailableForWriteException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("TRUST_SCORE_UNAVAILABLE", ex.getMessage(), traceId()));
    }

    // 503, same reasoning as handleUpstreamUnavailableForWrite immediately
    // above — a known, named upstream-dependency outage during a write
    // (suspend's own Connection Service step), not an unexpected programming
    // error. A separate error CODE from TRUST_SCORE_UNAVAILABLE so an admin
    // UI (or a human reading logs) can tell which of suspend's two
    // downstream legs actually failed.
    @ExceptionHandler(ConnectionServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleConnectionServiceUnavailable(ConnectionServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("CONNECTION_SERVICE_UNAVAILABLE", ex.getMessage(), traceId()));
    }

    // Same MDC-read convention as shared-java-lib's GlobalExceptionHandler and
    // every other service's local handler: traceId is only ever READ from the
    // OpenTelemetry-populated request-thread context, never minted here.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
