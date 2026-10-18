package com.shareconnectsave.rating.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

// Echo-back DTO for POST /ratings' 201 response (contracts/openapi/
// rating-service.yaml's Rating schema) - optional for client display per
// the ticket, built so the caller sees exactly what was recorded without a
// second GET. comment is passed straight through from the request rather
// than read back off the Rating entity: V001's ratings table has no comment
// column at all (see Rating's own class comment) - this response field is
// a request/response echo only, not yet a persisted value. A later ticket
// that adds a comment column changes this to a real read, not this record's
// shape.
public record RatingResponse(
        Long id,
        @JsonProperty("connection_id") Long connectionId,
        @JsonProperty("rater_id") Long raterId,
        @JsonProperty("rated_id") Long ratedId,
        List<String> tags,
        String comment,
        @JsonProperty("created_at") Instant createdAt
) {
}
