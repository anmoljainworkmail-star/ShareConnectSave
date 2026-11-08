package com.shareconnectsave.report_service.exception;

import com.shareconnectsave.shared.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
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
// Pattern: explicit precedence over an unordered shared advice. Both this
// class and the shared GlobalExceptionHandler match Exception-typed
// handlers, and with two unordered @RestControllerAdvice beans Spring picks
// by registration order. HIGHEST_PRECEDENCE makes this domain advice always
// win, so its specific handlers are never shadowed by the shared catch-all.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
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

    // Specific handlers beat the catch-all: a missing required parameter or
    // header is a caller mistake, so it must be 4xx, not the shared 500
    // INTERNAL_ERROR. Monitoring can then tell caller bugs from server faults.
    // Only the parameter/header NAME is echoed, never its value.
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleMissingInput(ServletRequestBindingException ex) {
        String name = ex instanceof MissingServletRequestParameterException missingParam
                ? missingParam.getParameterName()
                : ((MissingRequestHeaderException) ex).getHeaderName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MISSING_REQUIRED_INPUT",
                        "Required parameter or header '" + name + "' is missing.", traceId()));
    }

    // T053 — 403, not 401: X-User-Id/X-User-Role being present and
    // well-formed means the API Gateway already validated identity; this is
    // purely an authorization failure (a known, authenticated caller who is
    // just not an admin), which is what 403 Forbidden means as opposed to
    // 401 Unauthorized's "who even are you".
    @ExceptionHandler(NotAdminException.class)
    public ResponseEntity<ErrorResponse> handleNotAdmin(NotAdminException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ADMIN_ROLE_REQUIRED", ex.getMessage(), traceId()));
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
