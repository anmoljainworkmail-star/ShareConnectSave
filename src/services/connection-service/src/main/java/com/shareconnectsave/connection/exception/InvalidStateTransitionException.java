package com.shareconnectsave.connection.exception;

import com.shareconnectsave.connection.domain.ConnectionStatus;

// Thrown only by ConnectionServiceImpl's assertValidTransition helper — the
// SINGLE place that consults the VALID_TRANSITIONS map. Open/Closed
// (SOLID-O): this exception doesn't encode any transition rules itself, it
// only reports what the map already refused, so loosening or tightening the
// state machine later is purely a data change to that map.
public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(ConnectionStatus from, ConnectionStatus to) {
        super("Cannot transition connection from " + from + " to " + to);
    }
}
