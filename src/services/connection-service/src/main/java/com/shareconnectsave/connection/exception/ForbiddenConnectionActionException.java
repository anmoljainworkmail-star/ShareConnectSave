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

    private ForbiddenConnectionActionException(String message) {
        super(message);
    }

    // Static factory (named constructor): the public constructor above already
    // owns the (Long, Long) signature and its "not the recipient" wording, so a
    // second (Long, Long) overload for the read-side "not a participant" case
    // would be indistinguishable at the call site. A named factory says which
    // rule was broken without changing the existing constructor's behavior.
    public static ForbiddenConnectionActionException notParticipant(Long connectionId, Long callerId) {
        return new ForbiddenConnectionActionException(
                "User " + callerId + " is not a participant of connection " + connectionId);
    }
}
