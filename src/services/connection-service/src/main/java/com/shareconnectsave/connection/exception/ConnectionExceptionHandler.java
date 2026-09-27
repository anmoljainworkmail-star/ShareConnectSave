package com.shareconnectsave.connection.exception;

import com.shareconnectsave.shared.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Domain-specific @RestControllerAdvice, living ALONGSIDE (not replacing)
// the shared GlobalExceptionHandler that ExceptionHandlingConfig already
// @Imports. Single Responsibility (SOLID-S): GlobalExceptionHandler stays
// generic and shared across all five Java services (validation errors, the
// unhandled-exception catch-all); the five exceptions below are Connection
// Service's own vocabulary, so their HTTP-status/error-code mapping lives
// next to the feature code that raises them — the same split
// discovery-service's ScanController demonstrates with its own local
// @ExceptionHandler methods for ScanSessionNotFoundException and
// UserNotEligibleForDiscoveryException.
@RestControllerAdvice
public class ConnectionExceptionHandler {

    @ExceptionHandler(ConnectionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConnectionNotFound(ConnectionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CONNECTION_NOT_FOUND", ex.getMessage(), traceId()));
    }

    // 403, not 404: the caller is authenticated and the connection exists —
    // they are simply not its recipient. Authorization outcome, not a
    // missing-resource one (same reasoning as discovery-service's
    // UserNotEligibleForDiscoveryException handler).
    @ExceptionHandler(ForbiddenConnectionActionException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenConnectionAction(ForbiddenConnectionActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN_CONNECTION_ACTION", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_STATE_TRANSITION", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(ActiveConnectionExistsException.class)
    public ResponseEntity<ErrorResponse> handleActiveConnectionExists(ActiveConnectionExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ACTIVE_CONNECTION_EXISTS", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(RequestLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRequestLimitExceeded(RequestLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("REQUEST_LIMIT_EXCEEDED", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(SelfConnectionRequestException.class)
    public ResponseEntity<ErrorResponse> handleSelfConnectionRequest(SelfConnectionRequestException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SELF_CONNECTION_NOT_ALLOWED", ex.getMessage(), traceId()));
    }

    // Optimistic Concurrency Control: Hibernate throws this when a
    // @Version-guarded UPDATE matches zero rows because another transaction
    // already changed the row first (see ConnectionRequest.version). From
    // the loser's point of view, the transition it thought it was making is
    // now stale — the same client-visible outcome as an invalid state
    // transition, so it reuses that error code rather than inventing a new
    // one the frontend would have to special-case.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_STATE_TRANSITION", "Connection was modified concurrently; retry with the latest state", traceId()));
    }

    // Belt-and-suspenders with the createConnection() active-connection/
    // pending-count guards above: V001's filtered unique index
    // (idx_connection_requests_unique_pending) is the actual source of
    // truth that stops two concurrent requesters both inserting a PENDING
    // row for the same (requester, recipient) pair — the in-memory guard
    // checks can both pass before either INSERT commits. The DB constraint
    // is what closes that race; this handler just turns its violation into
    // the same 409 shape the rest of this controller already returns.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ACTIVE_CONNECTION_EXISTS", "A pending or active connection already exists for this pair", traceId()));
    }

    // Same MDC-read convention as shared-java-lib's GlobalExceptionHandler
    // and discovery-service's local handlers: traceId is only ever READ
    // from the OpenTelemetry-populated request-thread context, never minted
    // here — a generated id here would not exist in Jaeger, defeating the
    // entire point of correlating a client-visible error back to a trace.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
