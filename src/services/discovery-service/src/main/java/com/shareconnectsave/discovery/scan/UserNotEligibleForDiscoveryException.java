package com.shareconnectsave.discovery.scan;

// Domain-specific, same shape as ScanSessionNotFoundException: an
// unverified or trust-suspended user calling /scan/start is an expected
// client outcome (not a bug), so it gets its own stable error code and
// status rather than the shared GlobalExceptionHandler's generic 500.
public class UserNotEligibleForDiscoveryException extends RuntimeException {
    public UserNotEligibleForDiscoveryException(Long userId) {
        super("User " + userId + " is not eligible for discovery");
    }
}
