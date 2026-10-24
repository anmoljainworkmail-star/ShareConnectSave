package com.shareconnectsave.admin_service.exception;

// Thrown by ReviewQueueServiceImpl.reinstate (NEVER by a read path — the read
// paths catch client.UpstreamUnavailableException themselves and degrade
// gracefully instead) when Rating Service's current trust score cannot be
// fetched. Reinstate's own spec requires a REAL number to publish on
// trust.score.updated, never a fabricated one (see
// RatingServiceClient.getTrustScore's own comment) — this exception is what
// makes that failure visible to the admin as a clean, named 503 instead of a
// generic 500, while leaving User Service's status already flipped to
// active (see this class's own "retry is safe" note: setStatus is
// idempotent, so simply retrying POST /admin/users/{id}/reinstate once
// Rating Service recovers completes the operation with no special recovery
// step needed).
public class UpstreamUnavailableForWriteException extends RuntimeException {
    public UpstreamUnavailableForWriteException(String userId, Throwable cause) {
        super("Cannot safely reinstate user " + userId
                + ": Rating Service trust score unavailable, so no trust.score.updated event can be published. "
                + "User Service status is already set to active — retry this request once Rating Service recovers "
                + "(setStatus is idempotent, so retrying is safe).", cause);
    }
}
