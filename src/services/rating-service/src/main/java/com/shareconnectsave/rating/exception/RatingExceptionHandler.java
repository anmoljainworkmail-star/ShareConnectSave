package com.shareconnectsave.rating.exception;

import com.shareconnectsave.shared.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Domain-specific @RestControllerAdvice, living ALONGSIDE (not replacing)
// the shared GlobalExceptionHandler that ExceptionHandlingConfig already
// @Imports. Single Responsibility (SOLID-S): GlobalExceptionHandler stays
// generic and shared across all Java services (validation errors, the
// unhandled-exception catch-all); the exceptions below are Rating
// Service's own vocabulary, so their HTTP-status/error-code mapping lives
// next to the feature code that raises them - same split as
// connection-service's ConnectionExceptionHandler.
@RestControllerAdvice
public class RatingExceptionHandler {

    @ExceptionHandler(DuplicateRatingException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRating(DuplicateRatingException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("RATING_ALREADY_SUBMITTED", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(InvalidRatingTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRatingTag(InvalidRatingTagException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_RATING_TAG", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(ChatNotClosedException.class)
    public ResponseEntity<ErrorResponse> handleChatNotClosed(ChatNotClosedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("CHAT_NOT_CLOSED", ex.getMessage(), traceId()));
    }

    // Not in the ticket's explicit three - see SelfRatingException's own
    // class comment for why this mapping was added anyway.
    @ExceptionHandler(SelfRatingException.class)
    public ResponseEntity<ErrorResponse> handleSelfRating(SelfRatingException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("SELF_RATING_NOT_ALLOWED", ex.getMessage(), traceId()));
    }

    // Belt-and-suspenders with the submitRating() existsByConnectionIdAndRaterId()
    // guard above: V001's unique index (idx_ratings_connection_id_rater_id) is
    // the actual source of truth that stops two concurrent raters both inserting
    // a rating row for the same (connection_id, rater_id) pair — the in-memory
    // guard check can pass before the INSERT commits. The DB constraint is what
    // closes that race; this handler just turns its violation into the same 409
    // shape the rest of this controller already returns.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("RATING_ALREADY_SUBMITTED", "This connection has already been rated by this user", traceId()));
    }

    // Same MDC-read convention as shared-java-lib's GlobalExceptionHandler
    // and connection-service's local handler: traceId is only ever READ
    // from the OpenTelemetry-populated request-thread context, never minted
    // here - a generated id here would not exist in Jaeger, defeating the
    // entire point of correlating a client-visible error back to a trace.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
