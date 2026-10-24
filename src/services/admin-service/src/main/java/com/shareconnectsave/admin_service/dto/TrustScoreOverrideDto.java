package com.shareconnectsave.admin_service.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// PATCH /admin/users/{id}/trust-score's request body — SPECS.md's own
// `{ "score", "reason" }` shape verbatim. BigDecimal (not double), matching
// RatingServiceClientImpl's TrustScoreQueryResponseDto.trustScore field —
// trust scores are decimal and money-shaped-precision-sensitive the same way
// a currency amount is, so this stays consistent with how admin-service
// already represents a score everywhere else it touches one.
//
// "...Dto" suffix for an INBOUND request body, "...Response" for anything
// outbound — same split CLAUDE.md's Java Rules section documents
// (CreateConnectionDto / ConnectionResponse) and this service's own
// ReportCreateDto/ReinstateResponse already follow.
public record TrustScoreOverrideDto(
        @NotNull BigDecimal score,

        // @NotBlank, not merely @NotNull: an admin overriding an automated
        // decision must always leave a human-readable reason behind for the
        // audit log — a blank string would defeat the entire point of this
        // ticket's "human valve on automation" (see this ticket's own "Why
        // this ticket exists").
        @NotBlank String reason) {
}
