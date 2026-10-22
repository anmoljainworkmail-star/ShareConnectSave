package com.shareconnectsave.report_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Pattern: explicit snake_case wire contract via per-field @JsonProperty on
// a record — same idiom connection-service's CreateConnectionDto uses, not a
// project-wide Jackson naming strategy. Field names/shape here are
// contracts/openapi/report-service.yaml's ReportCreateRequest schema
// VERBATIM (reported_id, reason, connection_id, details) — this differs from
// SPECS.md's generic reported_user_id/context prose, same precedent
// rating-service's RatingSubmissionDto set for contract-over-SPECS.md.
//
// reportedId/connectionId stay String, not Long: this service's domain
// (Report, every user id in this platform's identity scheme) is
// UUID-shaped, unlike connection-service's BIGINT-keyed entities.
public record ReportCreateDto(
        @JsonProperty("reported_id") @NotBlank String reportedId,

        @JsonProperty("reason") @NotBlank String reason,

        // Nullable — only present when the report is filed from inside a
        // chat (REQUIREMENTS.md §8). No @NotBlank/@NotNull here: the
        // contract itself types this as ["string", "null"].
        @JsonProperty("connection_id") String connectionId,

        // Nullable, max 1000 chars per the contract's own maxLength — @Size
        // tolerates a null value (unlike @NotBlank), so an absent details
        // field never fails validation on its own.
        @JsonProperty("details") @Size(max = 1000) String details
) {
}
