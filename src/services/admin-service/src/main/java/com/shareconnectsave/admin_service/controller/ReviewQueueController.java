package com.shareconnectsave.admin_service.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.shareconnectsave.admin_service.dto.ReinstateResponse;
import com.shareconnectsave.admin_service.dto.ReviewQueueItem;
import com.shareconnectsave.admin_service.dto.SuspendResponse;
import com.shareconnectsave.admin_service.dto.SuspendUserRequest;
import com.shareconnectsave.admin_service.dto.UserDetailResponse;
import com.shareconnectsave.admin_service.service.ReviewQueueService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Pattern: thin controller — this class only translates HTTP in/out and
// delegates to ReviewQueueService, matching the project-wide "every
// controller delegates to a service layer" convention.
//
// Depends on the ReviewQueueService interface, not the impl — Dependency
// Inversion (SOLID-D).
//
// No @RequestHeader("X-User-Role") guard clause anywhere in this class, same
// as AdminPingController's own comment: SecurityConfig's
// .anyRequest().hasRole("ADMIN") already rejects a non-admin caller BEFORE
// the request ever reaches this controller. Report Service's ReportController
// (shipped before admin-service had a security filter chain) still does that
// check manually with an inline guard clause — this controller is what that
// pattern graduates INTO once a real filter chain exists, per SecurityConfig's
// own class comment.
//
// HTTP verb note (flagged, not silently "fixed"): contracts/openapi/
// admin-service.yaml specs PUT for /admin/users/{id}/reinstate, but both
// SPECS.md (line 1345: "POST /admin/users/:id/reinstate") and this ticket's
// own approved text say POST — this controller follows the approved ticket
// (the authoritative work order for T057), not the older OpenAPI file. Same
// for GET /admin/users/{id}: the contract only documents a narrower
// GET /admin/users/{id}/reports proxy, not this ticket's three-service
// aggregation — both gaps are worth a contracts/openapi/admin-service.yaml
// update in a follow-up ticket (/validate-contracts territory), not silently
// reconciled here.
@RestController
@RequiredArgsConstructor
@Slf4j
public class ReviewQueueController {

    private final ReviewQueueService reviewQueueService;

    @GetMapping("/admin/queue")
    public ResponseEntity<List<ReviewQueueItem>> getQueue() {
        return ResponseEntity.ok(reviewQueueService.getQueue());
    }

    @GetMapping("/admin/users/{id}")
    public ResponseEntity<UserDetailResponse> getUserDetail(@PathVariable("id") String id) {
        return ResponseEntity.ok(reviewQueueService.getUserDetail(id));
    }

    @PostMapping("/admin/users/{id}/reinstate")
    public ResponseEntity<ReinstateResponse> reinstate(
            @PathVariable("id") String id,
            // X-User-Id here identifies the ACTING ADMIN, not the target
            // account (that's the {id} path variable) — matching
            // admin-service.yaml's own XUserId parameter description
            // ("Identifies the acting admin for audit purposes"). Only ever
            // logged, never branched on: SecurityConfig already decided this
            // caller is an admin before this method runs.
            @RequestHeader("X-User-Id") String actingAdminId) {
        log.info("Admin {} reinstating user {}", actingAdminId, id);
        return ResponseEntity.ok(reviewQueueService.reinstate(id));
    }

    // T059 — the mirror image of reinstate() above, same URL family
    // (/admin/users/{id}/...), so it stays on THIS controller rather than a
    // separate AccountSuspendController: unlike T104's AdminConnectionController
    // (whose /admin/connections/users/{userId}/expire-active URL didn't nest
    // under an existing controller's mapping), /admin/users/{id}/suspend
    // nests naturally alongside /admin/users/{id} and
    // /admin/users/{id}/reinstate already handled right here.
    @PostMapping("/admin/users/{id}/suspend")
    public ResponseEntity<SuspendResponse> suspend(
            @PathVariable("id") String id,
            // Same ACTING-ADMIN-vs-TARGET-ACCOUNT split as reinstate()'s own
            // X-User-Id comment above.
            @RequestHeader("X-User-Id") String actingAdminId,
            @Valid @RequestBody SuspendUserRequest request) {
        log.info("Admin {} suspending user {} (reason: {})", actingAdminId, id, request.reason());
        return ResponseEntity.ok(reviewQueueService.suspend(id, request));
    }
}
