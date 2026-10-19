package com.shareconnectsave.rating.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/rating.submitted.schema.json. Snake_case field names via
// per-field @JsonProperty - same convention as connection-service's
// ConnectionAcceptedEvent - because this project has no project-wide
// Jackson naming strategy; ObjectMapper.writeValueAsString(this) in
// OutboxServiceImpl therefore already produces the exact wire shape
// Rating Service's own self-consuming trust-score recalculation listener
// (T044's RatingSubmittedConsumer) expects, with zero extra configuration -
// this record is deserialized by that same consumer, not just serialized by
// OutboxServiceImpl, so it is genuinely used on both sides of the wire.
//
// @JsonIgnoreProperties(ignoreUnknown = true): added in T044 now that this
// record is also a deserialization target (Postel's Law at a service
// boundary) - same reasoning as connection-service's TrustScoreUpdatedEvent,
// even though here the producer and consumer are the same service and
// therefore always in lockstep on this one field set today.
//
// Per this platform's established convention (see RequestLimit's own class
// comment on the UNLIMITED/THROTTLED/SUSPENDED -> integer boundary
// translation), every id on the wire here is a STRINGIFIED Long, not a
// java.util.UUID - Rating.id/raterId/ratedId are all Long (BIGINT), so each
// is serialized via .toString(), even though the JSON schema's `format:
// uuid` annotation (a project-wide placeholder, not enforced) suggests
// otherwise.
//
// tags is List<RatingTag>, not List<String>: Jackson serializes each
// RatingTag element through its own @JsonValue (RatingTag.toWireValue), so
// this record produces the schema's Title Case strings ("On time") directly
// - there is no manual string-building step for the service layer to get
// wrong.
@JsonIgnoreProperties(ignoreUnknown = true)
public record RatingSubmittedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("rating_id") String ratingId,
        @JsonProperty("rater_id") String raterId,
        @JsonProperty("rated_id") String ratedId,
        @JsonProperty("tags") List<RatingTag> tags
) {
}
