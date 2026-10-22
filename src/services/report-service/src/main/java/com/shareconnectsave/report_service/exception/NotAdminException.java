package com.shareconnectsave.report_service.exception;

// Guard against a non-admin caller reading GET /reports/queue: that endpoint
// surfaces every escalated report's reporter/reportedId/reason across the
// WHOLE platform, not one user's own data, so it needs its own authorization
// check distinct from the JWT-identity trust boundary every other endpoint
// here already relies on. No Spring Security filter chain exists in this
// service (that is T056's job, scoped to admin-service only, and ships in a
// LATER phase) — so this follows the same plain @RequestHeader read + guard
// clause + domain-specific exception shape this codebase already uses
// elsewhere (discovery-service's UserNotEligibleForDiscoveryException,
// rating-service's SelfRatingException), caught by this service's own local
// ReportExceptionHandler rather than a generic 500.
public class NotAdminException extends RuntimeException {
    public NotAdminException(String userId) {
        super("User " + userId + " is not authorized to view the report escalation queue");
    }
}
