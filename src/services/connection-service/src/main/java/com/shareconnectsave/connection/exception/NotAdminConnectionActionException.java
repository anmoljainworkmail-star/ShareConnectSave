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

    // T107 — GET /admin/connections/stats' guard clause needs the exact same
    // exception TYPE (so ConnectionExceptionHandler's one existing
    // @ExceptionHandler keeps mapping both admin actions to 403/
    // ADMIN_ROLE_REQUIRED without a second handler method), but has no
    // target user id to report — this endpoint isn't acting on any one
    // user's connections. An overloaded constructor, not a second exception
    // class, is the smaller change: Liskov Substitution (SOLID-L) still
    // holds (every caller of this exception type gets a RuntimeException
    // with a sensible message), and the admin-role guard stays a single,
    // reusable concept across both methods on this controller.
    public NotAdminConnectionActionException() {
        super("Caller is not authorized to view connection stats");
    }
}
