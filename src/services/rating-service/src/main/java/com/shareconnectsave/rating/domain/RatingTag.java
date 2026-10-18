package com.shareconnectsave.rating.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.shareconnectsave.rating.exception.InvalidRatingTagException;

// Open/Closed Principle (SOLID-O): the allowed rating vocabulary is DATA -
// this enum's constants - not a chain of if/else string comparisons in a
// validator. Adding a new tag (e.g. "Great conversation") means adding one
// constant here with its wire value; RatingServiceImpl.submitRating's
// validation loop never changes.
//
// Wire-format mapping: contracts/openapi/rating-service.yaml's RatingTag
// schema and contracts/kafka/rating.submitted.schema.json both use
// human-readable, Title Case strings with spaces/punctuation ("On time",
// "Didn't show up") as the single shared vocabulary for BOTH the HTTP and
// Kafka boundaries. Those strings are not valid Java identifiers, so this
// enum cannot rely on Jackson's default enum (de)serialization (which would
// read/write the Java constant name, ON_TIME, instead of the contract's
// actual string) - @JsonValue/@JsonCreator make the mapping explicit and
// bidirectional instead.
public enum RatingTag {
    FRIENDLY("Friendly"),
    RESPECTFUL("Respectful"),
    ON_TIME("On time"),
    GOOD_COMMUNICATION("Good communication"),
    WOULD_TRAVEL_AGAIN("Would travel again"),
    DIDNT_SHOW_UP("Didn't show up"),
    FAKE_DESTINATION("Fake destination"),
    ABUSIVE("Abusive"),
    SPAM("Spam"),
    ASKED_FOR_MONEY("Asked for money");

    private final String wireValue;

    RatingTag(String wireValue) {
        this.wireValue = wireValue;
    }

    // @JsonValue: whenever Jackson serializes a RatingTag (directly, or as
    // an element of a List<RatingTag> like RatingSubmittedEvent.tags), it
    // writes THIS string instead of the enum constant's Java name - this is
    // what lets the outbox payload and the Kafka event match
    // rating.submitted.schema.json's Title Case strings with zero manual
    // string-building in the service layer.
    @JsonValue
    public String toWireValue() {
        return wireValue;
    }

    // @JsonCreator: the inverse mapping, used explicitly by
    // RatingServiceImpl to validate each raw tag string the client
    // submitted (Guard clause, per the ticket's validation order) - not
    // relied on for automatic deserialization of RatingSubmissionDto.tags,
    // which stays a List<String> precisely so an invalid tag surfaces as
    // THIS class's own InvalidRatingTagException (mapped to 400
    // INVALID_RATING_TAG by RatingExceptionHandler), not as a generic
    // Jackson deserialization failure the shared GlobalExceptionHandler
    // would turn into an undifferentiated 400/500.
    @JsonCreator
    public static RatingTag fromWireValue(String wireValue) {
        for (RatingTag tag : values()) {
            if (tag.wireValue.equals(wireValue)) {
                return tag;
            }
        }
        throw new InvalidRatingTagException(wireValue);
    }
}
