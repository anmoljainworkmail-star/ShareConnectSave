package com.shareconnectsave.rating.exception;

// Thrown by RatingTag.fromWireValue when a submitted tag string is not a
// member of the RatingTag enum (Open/Closed, SOLID-O - the validation rule
// is "is this value in the set", never a hardcoded if/else chain of
// allowed strings).
public class InvalidRatingTagException extends RuntimeException {
    public InvalidRatingTagException(String rawTag) {
        super("'" + rawTag + "' is not a recognized rating tag");
    }
}
