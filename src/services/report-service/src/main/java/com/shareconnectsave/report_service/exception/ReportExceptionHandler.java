package com.shareconnectsave.report_service.exception;

import com.shareconnectsave.shared.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Domain-specific @RestControllerAdvice, living ALONGSIDE (not replacing)
// the shared GlobalExceptionHandler that ExceptionHandlingConfig @Imports.
// Single Responsibility (SOLID-S): GlobalExceptionHandler stays generic and
// shared across every Java service (validation errors, the
// unhandled-exception catch-all); the two exceptions below are Report
// Service's own vocabulary, so their HTTP-status/error-code mapping lives
// next to the feature code that raises them — same split as
// connection-service's ConnectionExceptionHandler and rating-service's
// RatingExceptionHandler.
@RestControllerAdvice
public class ReportExceptionHandler {

    // 409, not 400 — same choice connection-service made for
    // SelfConnectionRequestException: the request is well-formed, it is
    // just not a legal action for this caller to take against themselves.
    @ExceptionHandler(SelfReportException.class)
    public ResponseEntity<ErrorResponse> handleSelfReport(SelfReportException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SELF_REPORT_NOT_ALLOWED", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(InvalidReportReasonException.class)
    public ResponseEntity<ErrorResponse> handleInvalidReportReason(InvalidReportReasonException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REPORT_REASON", ex.getMessage(), traceId()));
    }

    // Same MDC-read convention as shared-java-lib's GlobalExceptionHandler
    // and every other service's local handler: traceId is only ever READ
    // from the OpenTelemetry-populated request-thread context, never minted
    // here — a generated id here would not exist in Jaeger, defeating the
    // entire point of correlating a client-visible error back to a trace.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
