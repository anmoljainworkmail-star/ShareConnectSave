package com.shareconnectsave.connection.exception;

// Guard against a user connecting to themselves: without this check, a user
// could send a connection request naming their own id as recipient, then
// (as their own recipient) accept it — a degenerate "connection" that
// satisfies no real matching purpose and would corrupt the "one active
// connection" invariant the rest of this service relies on. Thrown from
// ConnectionServiceImpl.createConnection before any DB read/write happens,
// so it never even reaches the request-limit or active-connection guards.
public class SelfConnectionRequestException extends RuntimeException {
    public SelfConnectionRequestException(Long userId) {
        super("User " + userId + " cannot send a connection request to themselves");
    }
}
