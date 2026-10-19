package com.shareconnectsave.rating.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

// Response DTO for T045's GET /trust/:userId - deliberately a DIFFERENT type
// from TrustScore (the JPA entity). Same boundary-DTO split as RatingResponse
// vs. Rating: TrustScore's column shape (request_limit stored as the
// RequestLimit STRING enum, per CLAUDE.md's Open/Closed rule) can evolve
// without this service's outward HTTP contract moving in lockstep, and this
// is the one place RequestLimit.wireValue() converts the enum category to
// the plain integer contracts/openapi/rating-service.yaml's TrustScoreResponse
// already declares (same boundary-mapping discipline T044's Kafka producer
// applies to the same enum, see RequestLimit's own class comment).
//
// snake_case @JsonProperty names, matching every other wire DTO in this
// service (RatingResponse, RatingSubmitRequest) and discovery-service's own
// PublicUserProfileResponse convention.
public record TrustScoreQueryResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("trust_score") BigDecimal trustScore,
        @JsonProperty("request_limit") int requestLimit,
        @JsonProperty("is_trusted") boolean isTrusted,
        @JsonProperty("rating_count") int ratingCount) {
}
