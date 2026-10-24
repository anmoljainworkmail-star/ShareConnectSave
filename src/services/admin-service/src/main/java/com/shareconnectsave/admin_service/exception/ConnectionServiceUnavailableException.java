package com.shareconnectsave.admin_service.exception;

// Thrown by ReviewQueueServiceImpl.suspend (never by a read path — this
// service has none that call Connection Service) once
// ConnectionServiceClientImpl's @CircuitBreaker fallback has already turned
// the underlying failure into the shared client.UpstreamUnavailableException
// and this method decides what that means for a suspend specifically.
// Mirrors UpstreamUnavailableForWriteException's own reasoning exactly (read
// that class's comment first): a WRITE must never be left looking like it
// silently succeeded. By the time this can be thrown, suspend's own ordering
// spec guarantees User Service already succeeded — the account IS already
// suspended — but this user's active connections have NOT been expired and
// no trust.score.updated event has been published, so the request must still
// fail rather than return 200 over a half-finished suspend. Retrying
// POST /admin/users/{id}/suspend afterward is always safe: setStatus
// ("suspended") is idempotent (setting an already-suspended account to
// suspended again is a no-op) and T104's expire-active is naturally
// idempotent too (there are no PENDING/ACCEPTED connections left to expire
// the second time around), so no compensating "un-suspend" call is needed
// here.
public class ConnectionServiceUnavailableException extends RuntimeException {
    public ConnectionServiceUnavailableException(String userId, Throwable cause) {
        super("Cannot complete suspend for user " + userId
                + ": Connection Service is unavailable, so this user's active connection requests could not be "
                + "expired and no trust.score.updated event was published. User Service status is already set to "
                + "suspended — retry this request once Connection Service recovers (expire-active is idempotent, "
                + "so retrying is safe).", cause);
    }
}
