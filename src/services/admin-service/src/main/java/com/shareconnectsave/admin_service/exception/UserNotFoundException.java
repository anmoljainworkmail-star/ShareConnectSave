package com.shareconnectsave.admin_service.exception;

// Domain-specific: a genuine 404 from User Service (UserServiceClient.getUser
// returned null, meaning the id truly does not exist — see that method's own
// comment on why null is reserved for exactly this case) deserves its own
// stable error code, not GlobalExceptionHandler's generic 500 catch-all. Same
// "own vocabulary gets its own exception" precedent as discovery-service's
// ScanSessionNotFoundException and report-service's SelfReportException.
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String userId) {
        super("No user found with id " + userId);
    }
}
