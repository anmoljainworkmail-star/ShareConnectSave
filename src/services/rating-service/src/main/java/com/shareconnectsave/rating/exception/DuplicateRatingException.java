package com.shareconnectsave.rating.exception;

// Concurrency guard, mirrored at the DB layer by V001's
// idx_ratings_connection_id_rater_id UNIQUE index: this exception is the
// application-level check (RatingRepository.existsByConnectionIdAndRaterId)
// that lets a duplicate submission fail fast with a clean 409 before ever
// reaching the DB - the unique index is still the last line of defense
// against a genuine race between two concurrent requests, same relationship
// as connection-service's ActiveConnectionExistsException/
// handleDataIntegrityViolation pair.
public class DuplicateRatingException extends RuntimeException {
    public DuplicateRatingException(Long connectionId, Long raterId) {
        super("User " + raterId + " has already rated connection " + connectionId);
    }
}
