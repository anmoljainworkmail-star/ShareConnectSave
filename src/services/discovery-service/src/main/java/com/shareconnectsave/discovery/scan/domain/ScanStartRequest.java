package com.shareconnectsave.discovery.scan.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

// Pattern: explicit snake_case wire contract. Jackson defaults a Java record's
// component names to camelCase with zero configuration (see ErrorResponse's
// note on the same point) — this project has no project-wide
// PropertyNamingStrategy, so every field that needs to match the rest of the
// API's snake_case JSON convention is annotated individually, the same
// per-field approach User Service's C# DTOs use with [JsonPropertyName].
public record ScanStartRequest(
        @JsonProperty("destination_lat") @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double destinationLat,
        @JsonProperty("destination_lng") @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double destinationLng,
        @JsonProperty("destination_label") String destinationLabel,
        @JsonProperty("departure_time") Instant departureTime
) {
}
