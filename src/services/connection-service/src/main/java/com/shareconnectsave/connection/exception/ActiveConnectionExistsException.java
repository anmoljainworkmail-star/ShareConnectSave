package com.shareconnectsave.connection.exception;

// "One user, one open connection at a time" — thrown from two different
// guard sites in ConnectionServiceImpl: createConnection (a single user,
// the requester, already has an ACCEPTED connection) and acceptConnection
// (accepting THIS request would leave either the requester or the
// recipient in two ACCEPTED connections simultaneously). Two constructors
// cover the single-user and two-user call sites without forcing either one
// to invent a value it doesn't have.
public class ActiveConnectionExistsException extends RuntimeException {
    public ActiveConnectionExistsException(Long userId) {
        super("User " + userId + " already has an active connection");
    }

    public ActiveConnectionExistsException(Long requesterId, Long recipientId) {
        super("Requester " + requesterId + " or recipient " + recipientId + " already has an active connection");
    }
}
