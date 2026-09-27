package com.shareconnectsave.connection.exception;

// Same shape as discovery-service's ScanSessionNotFoundException: a lookup
// by id that fails is an expected client outcome (a stale link, a typo'd
// path variable), not a bug — it earns its own stable 404 + error code
// instead of falling through to an NPE or GlobalExceptionHandler's generic
// 500 catch-all. Not named in the ticket's exception list, but the
// implementation notes explicitly call for it: every other service in this
// codebase has an explicit not-found exception for a lookup-by-id.
public class ConnectionNotFoundException extends RuntimeException {
    public ConnectionNotFoundException(Long connectionId) {
        super("Connection " + connectionId + " not found");
    }
}
