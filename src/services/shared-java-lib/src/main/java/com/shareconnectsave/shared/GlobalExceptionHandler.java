package com.shareconnectsave.shared;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Pattern: Single Responsibility (SOLID-S) — this class does exactly one job:
// translate exceptions into the shared ErrorResponse envelope. It never opens a
// repository, never publishes to Kafka, never contains business rules. That
// narrowness is precisely what makes it safe to share: all five Java services
// (Discovery, Connection, Rating, Report, Admin) declare shared-java-lib as a Maven
// dependency and get identical error behaviour, instead of five copy-pasted
// @ControllerAdvice classes that quietly drift apart over time.
//
// Pattern: DRY via a shared library, not a base class. Composition (each service's
// component scan picks this bean up because it's on the classpath and annotated)
// beats inheritance here — services don't extend anything to get this behaviour,
// they just add a dependency.
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Maps bean-validation failures (e.g. @NotBlank, @Email on a request DTO) to a
    // 400 with a stable machine-readable code, so the Angular client can branch on
    // `code` without parsing prose.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Validation failed.";

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, traceId()));
    }

    // Catch-all for anything a controller didn't anticipate. Deliberately generic:
    // the message never echoes ex.getMessage() because that can leak internals
    // (SQL, stack details, class names) straight into an HTTP response body.
    // The traceId is how an operator finds out what actually happened, in Jaeger,
    // without the client ever seeing it.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandled(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred.", traceId()));
    }

    // Pattern: Distributed Tracing integration. traceId is read from MDC — populated
    // by OpenTelemetry's Spring instrumentation on the current request thread — never
    // minted here as a random UUID. A generated ID would not exist in Jaeger, which
    // defeats the entire purpose of putting it in the error envelope: correlating a
    // client-visible error back to the exact trace that produced it.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
