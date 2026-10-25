package com.shareconnectsave.report_service.exception;

// Guard against a non-admin caller reading an admin-only report endpoint:
// GET /reports/queue (T053) and GET /reports/top-reported (T108) both
// surface report data across the WHOLE platform, not one user's own data,
// so each needs its own authorization check distinct from the
// JWT-identity trust boundary every other endpoint here already relies on.
// No Spring Security filter chain exists in this service (that is T056's
// job, scoped to admin-service only, and ships in a LATER phase) — so this
// follows the same plain @RequestHeader read + guard clause +
// domain-specific exception shape this codebase already uses elsewhere
// (discovery-service's UserNotEligibleForDiscoveryException,
// rating-service's SelfRatingException), caught by this service's own local
// ReportExceptionHandler rather than a generic 500.
//
// Reused (not duplicated) across both admin-only endpoints in
// ReportController — one exception type for "this is an admin-only report
// action and the caller isn't one", per this ticket's own "copy this
// controller's existing admin-guard exception type rather than inventing a
// new one" instruction.
public class NotAdminException extends RuntimeException {
    public NotAdminException(String userId) {
        super("User " + userId + " is not authorized to perform this admin-only report action");
    }
}
