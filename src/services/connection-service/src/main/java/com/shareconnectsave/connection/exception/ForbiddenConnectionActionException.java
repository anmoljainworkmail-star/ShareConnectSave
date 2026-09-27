package com.shareconnectsave.connection.exception;

// API Gateway (trust boundary): X-User-Id is trusted as-is — already
// validated by YARP before this request ever reached Connection Service, so
// this exception is never "who are you" (401). It is "you are a genuine,
// authenticated user who simply isn't the recipient of THIS connection" — a
// pure authorization outcome (403), thrown by ConnectionServiceImpl before
// any state-machine or business-rule check runs, so an unauthorized caller
// never learns anything about a connection's current status.
public class ForbiddenConnectionActionException extends RuntimeException {
    public ForbiddenConnectionActionException(Long connectionId, Long callerId) {
        super("User " + callerId + " is not the recipient of connection " + connectionId);
    }
}
