package com.shareconnectsave.admin_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.shareconnectsave.admin_service.dto.TrustScoreOverrideDto;
import com.shareconnectsave.admin_service.dto.TrustScoreOverrideResponse;
import com.shareconnectsave.admin_service.service.AdminTrustScoreService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Pattern: thin controller — this class only translates HTTP in/out and
// delegates to AdminTrustScoreService, matching the project-wide "every
// controller delegates to a service layer" convention (see
// ReviewQueueController's own class comment for the same statement in this
// exact service).
//
// Depends on the AdminTrustScoreService interface, not the impl —
// Dependency Inversion (SOLID-D).
//
// No @RequestHeader("X-User-Role") guard clause here either, same reasoning
// as ReviewQueueController's own comment: SecurityConfig's
// .anyRequest().hasRole("ADMIN") already rejects a non-admin caller before
// this controller method ever runs.
@RestController
@RequiredArgsConstructor
@Slf4j
public class AdminTrustScoreController {

    private final AdminTrustScoreService adminTrustScoreService;

    @PatchMapping("/admin/users/{id}/trust-score")
    public ResponseEntity<TrustScoreOverrideResponse> overrideTrustScore(
            @PathVariable("id") String id,
            // X-User-Id here identifies the ACTING ADMIN, not the target
            // account (that's the {id} path variable) — same split
            // ReviewQueueController.reinstate's own comment documents for
            // this exact header on this exact kind of endpoint.
            @RequestHeader("X-User-Id") String actingAdminId,
            @Valid @RequestBody TrustScoreOverrideDto dto) {
        log.info("Admin {} overriding trust score for user {} to {} (reason: {})",
                actingAdminId, id, dto.score(), dto.reason());
        return ResponseEntity.ok(adminTrustScoreService.overrideTrustScore(actingAdminId, id, dto));
    }
}
