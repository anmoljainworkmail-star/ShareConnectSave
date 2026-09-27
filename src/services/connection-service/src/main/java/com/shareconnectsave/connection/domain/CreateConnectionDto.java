package com.shareconnectsave.connection.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

// Pattern: explicit snake_case wire contract, same per-field @JsonProperty
// approach as discovery-service's ScanStartRequest — this project has no
// project-wide Jackson PropertyNamingStrategy, so a record's camelCase
// component names are annotated individually to match the rest of the API's
// snake_case JSON convention (contracts/openapi/connection-service.yaml's
// ConnectionCreateRequest.recipient_id).
//
// recipientId is Long, not UUID: see the ticket's deviation note —
// V001__connection_service_initial_schema.sql already types
// connection_requests.recipient_id as BIGINT, matching the platform-wide
// convention every other Java service's X-User-Id header uses.
public record CreateConnectionDto(
        @JsonProperty("recipient_id") @NotNull Long recipientId
) {
}
