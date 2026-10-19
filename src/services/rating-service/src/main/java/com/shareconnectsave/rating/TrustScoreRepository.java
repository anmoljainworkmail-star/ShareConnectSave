package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.TrustScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Pattern: Repository - plain JpaRepository, no "I" prefix, same convention
// as RatingRepository and connection-service's OutboxRepository (see
// RatingRepository's own class comment for the full rationale).
//
// findByUserId backs BOTH of this ticket's (T044) read paths: the upsert in
// TrustScoreRecalculationServiceImpl ("does this user already have a row? if
// so, update it in place") and RatingSubmittedConsumer's "what report count
// was this user last known to have?" lookup. idx_trust_scores_user_id
// (V001, unique) is what makes this a safe single-row lookup rather than a
// query that could ever return more than one result.
public interface TrustScoreRepository extends JpaRepository<TrustScore, Long> {

    Optional<TrustScore> findByUserId(Long userId);
}
