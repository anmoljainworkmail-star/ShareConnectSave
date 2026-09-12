package com.shareconnectsave.discovery.scan.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// No session_id field here on purpose: the ticket's contract for this
// endpoint is just { lat, lng } — the active session is resolved server-side
// from the X-User-Id header (see ScanSessionService.requireActiveSession),
// the same "identity comes from the gateway-validated header, not the
// request body" rule the java-spring-boot skill documents for every other
// endpoint in this service.
public record ScanLocationRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng
) {
}
