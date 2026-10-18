package com.shareconnectsave.rating.exception;

// Thrown when ChatClosureChecker reports a connection's chat as not yet
// closed. See ChatClosureChecker's own class comment for the known,
// documented T044 sequencing gap this exception's real data source depends
// on - the exception and its 400 CHAT_NOT_CLOSED mapping exist now so T044
// only has to swap the checker implementation, not add new wiring.
public class ChatNotClosedException extends RuntimeException {
    public ChatNotClosedException(Long connectionId) {
        super("Chat for connection " + connectionId + " is not closed yet");
    }
}
