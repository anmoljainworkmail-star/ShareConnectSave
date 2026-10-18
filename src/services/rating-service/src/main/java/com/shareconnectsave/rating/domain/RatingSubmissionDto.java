package com.shareconnectsave.rating.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// Pattern: explicit snake_case wire contract via per-field @JsonProperty -
// same convention as CreateConnectionDto, because this project has no
// project-wide Jackson PropertyNamingStrategy. Field names and the optional
// comment match contracts/openapi/rating-service.yaml's RatingSubmitRequest
// exactly: connection_id, rated_id, tags, comment - NOT rated_user_id,
// which matches neither the contract nor Rating.ratedId.
//
// tags stays List<String> (raw wire strings), not List<RatingTag>,
// deliberately: binding straight to RatingTag here would let an invalid tag
// fail as a Jackson deserialization error before this DTO even exists,
// which the shared GlobalExceptionHandler's generic Exception handler would
// turn into an undifferentiated response - the ticket's INVALID_RATING_TAG
// 400 instead comes from RatingServiceImpl explicitly calling
// RatingTag.fromWireValue() per tag, as its own guard clause.
//
// connectionId/ratedId are Long (BIGINT), not UUID - same platform-wide
// convention CreateConnectionDto already applies: every internal id in this
// codebase is BIGINT, regardless of what the OpenAPI contract's `format:
// uuid` annotation (a project-wide placeholder, not enforced) suggests.
public record RatingSubmissionDto(
        @JsonProperty("connection_id") @NotNull Long connectionId,
        @JsonProperty("rated_id") @NotNull Long ratedId,
        @JsonProperty("tags") @NotEmpty List<String> tags,
        @JsonProperty("comment") @Size(max = 500) String comment
) {
}
