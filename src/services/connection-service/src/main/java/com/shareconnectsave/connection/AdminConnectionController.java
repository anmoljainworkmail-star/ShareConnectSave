package com.shareconnectsave.connection;

import com.shareconnectsave.connection.domain.ExpireActiveConnectionsResponse;
import com.shareconnectsave.connection.exception.NotAdminConnectionActionException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// T104 — kept SEPARATE from ConnectionController rather than added as a
// method on it, for two reasons checked against this codebase's own
// precedent first: (1) the URL doesn't nest under /connections
// (ticket spec: /admin/connections/users/{userId}/expire-active), so a
// method here would need its own class-level @RequestMapping anyway; (2)
// this endpoint's trust boundary is different in kind, not degree — every
// ConnectionController method trusts "X-User-Id is a genuine, authenticated
// caller acting on their own connections," this one instead requires
// "X-User-Id is an admin acting on SOMEONE ELSE's connections." Report
// Service puts its one admin endpoint (GET /reports/queue) inside its
// existing domain controller because that URL nests naturally under
// /reports; here it doesn't, so a dedicated controller is the better fit,
// not a stylistic preference for its own sake.
//
// Pattern: thin controller — this method only translates HTTP in/out and
// delegates to ConnectionService, same convention as ConnectionController.
// Dependency Inversion (SOLID-D): depends on the ConnectionService
// interface, never ConnectionServiceImpl.
@RestController
@RequestMapping("/admin/connections")
@RequiredArgsConstructor
public class AdminConnectionController {

    private static final String ADMIN_ROLE = "admin";

    private final ConnectionService connectionService;

    @PutMapping("/users/{userId}/expire-active")
    public ResponseEntity<ExpireActiveConnectionsResponse> expireActiveConnections(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @PathVariable Long userId) {
        // Authorization Guard Clause (early return): rejects BEFORE calling
        // into ConnectionService, so the service layer never has to know
        // authorization exists at all (Single Responsibility, SOLID-S) —
        // same shape as ReportController's admin-queue guard. required =
        // false on the header (rather than letting Spring's default
        // MissingRequestHeaderException handling kick in) is what lets a
        // MISSING header and a WRONG header value collapse into the exact
        // same 403, as the ticket requires, instead of a 400 for one case
        // and a 403 for the other.
        if (!ADMIN_ROLE.equals(role)) {
            throw new NotAdminConnectionActionException(userId);
        }

        int expiredCount = connectionService.expireActiveConnectionsForUser(userId);
        return ResponseEntity.ok(new ExpireActiveConnectionsResponse(expiredCount));
    }
}
