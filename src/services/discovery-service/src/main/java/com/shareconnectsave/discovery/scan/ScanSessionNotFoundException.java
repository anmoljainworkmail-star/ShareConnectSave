package com.shareconnectsave.discovery.scan;

// Domain-specific: "you have no active scan" is an expected client outcome
// (call /scan/stop or /scan/location without ever starting one), not a bug —
// it deserves its own 404 + stable error code, not the shared
// GlobalExceptionHandler's generic 500 catch-all.
public class ScanSessionNotFoundException extends RuntimeException {
    public ScanSessionNotFoundException(Long userId) {
        super("No active scan session for user " + userId);
    }
}
