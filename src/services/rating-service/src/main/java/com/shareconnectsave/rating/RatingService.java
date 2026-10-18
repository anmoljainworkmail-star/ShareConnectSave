package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.RatingResponse;
import com.shareconnectsave.rating.domain.RatingSubmissionDto;

// Dependency Inversion (SOLID-D): RatingController depends on THIS
// interface, never on RatingServiceImpl directly - same shape as
// connection-service's ConnectionService / ConnectionServiceImpl pair.
public interface RatingService {

    RatingResponse submitRating(Long raterId, RatingSubmissionDto dto);
}
