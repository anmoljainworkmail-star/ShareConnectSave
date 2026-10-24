package com.shareconnectsave.connection.exception;

// T104 — guards PUT /admin/connections/users/{userId}/expire-active. No
// Spring Security filter chain exists in this service (that is T056's job,
// scoped to admin-service only) — so this follows the same plain
// @RequestHeader read + guard clause + domain-specific exception shape
// report-service's NotAdminException already established for its own
// GET /reports/queue admin endpoint, rather than inventing an authorization
// framework just for this one method. API Gateway (trust boundary): the
// VALUE of X-User-Role, when present, is fully trusted — YARP already
// validated the caller's JWT and set this header; this exception only means
// "for THIS request, that header was missing or wasn't admin," never "the
// token itself looked forged."
public class NotAdminConnectionActionException extends RuntimeException {
    public NotAdminConnectionActionException(Long targetUserId) {
        super("Caller is not authorized to bulk-expire connections for user " + targetUserId);
    }
}
