package com.shareconnectsave.admin_service.dto;

import jakarta.validation.constraints.NotBlank;

// POST /admin/users/{id}/suspend's request body — this ticket's own
// `{ "reason" }` shape verbatim. "...Dto" suffix for an INBOUND request body
// (CLAUDE.md's Java Rules section: ...Dto for input, ...Response for
// output), same split TrustScoreOverrideDto already follows in this service.
//
// @NotBlank, not merely @NotNull — same reasoning as
// TrustScoreOverrideDto.reason: an admin taking an account out of
// circulation should always leave a human-readable reason behind, even
// though this ticket (unlike T058's trust-score override) does not persist
// it to an audit store — ReviewQueueController logs it for operator
// visibility instead. Known pre-existing gap, not this ticket's to fix:
// admin-service's pom.xml still omits spring-boot-starter-validation (see
// its own comment, now stale since T058 added the first @Valid DTO) — the
// annotation is present for documentation/intent and will start actually
// enforcing a blank reason the moment that starter is added, same latent
// state TrustScoreOverrideDto.reason is already in.
public record SuspendUserRequest(@NotBlank String reason) {
}
