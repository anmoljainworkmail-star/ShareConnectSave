package com.shareconnectsave.connection.exception;

// Event-Driven Architecture: the limit reported here came from a LOCAL read
// of IRequestLimitCache — a value T033's trust.score.updated Kafka consumer
// will (eventually) keep current — never a synchronous call to
// Rating/Trust Service made just to answer this one request. Distinct from
// ActiveConnectionExistsException even though both are 409s: this is a
// throttle (too many PENDING outbound requests), not "already
// connected to someone".
public class RequestLimitExceededException extends RuntimeException {
    public RequestLimitExceededException(Long userId, int limit) {
        super("User " + userId + " already has " + limit + " pending outbound requests");
    }
}
