package com.shareconnectsave.admin_service.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

// Pattern: thin controller — this class only translates HTTP in/out and
// delegates to DashboardStatsService, matching the project-wide "every
// controller delegates to a service layer" convention (ReviewQueueController).
//
// Depends on the DashboardStatsService interface, not the impl — Dependency
// Inversion (SOLID-D).
//
// No @RequestHeader("X-User-Role") guard clause here, same as
// ReviewQueueController's own comment: SecurityConfig's
// .anyRequest().hasRole("ADMIN") already rejects a non-admin caller before
// the request ever reaches this controller.
@RestController
@RequiredArgsConstructor
public class DashboardStatsController {

    private final DashboardStatsService dashboardStatsService;

    @GetMapping("/admin/stats")
    public ResponseEntity<DashboardStatsResponse> getStats() {
        return ResponseEntity.ok(dashboardStatsService.getStats());
    }
}
