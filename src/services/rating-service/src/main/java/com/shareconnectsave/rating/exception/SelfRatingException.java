package com.shareconnectsave.rating.exception;

// Not called out as one of the ticket's three named domain exceptions, but
// added for the same reason connection-service has its own
// SelfConnectionRequestException: without a dedicated exception + local
// handler mapping, a self-rating guard-clause failure would have to throw
// something the shared GlobalExceptionHandler's generic Exception catch-all
// turns into a 500 - wrong status for a client input error, and
// inconsistent with how every other validation guard in this service (and
// connection-service's own analogous self-connection guard) is handled.
public class SelfRatingException extends RuntimeException {
    public SelfRatingException(Long userId) {
        super("User " + userId + " cannot rate themselves");
    }
}
