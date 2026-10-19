package com.shareconnectsave.rating.trustscore;

import com.shareconnectsave.rating.domain.TrustScoreQueryResponse;

// Single Responsibility (SOLID-S): this interface's one implementation only
// READS and formats already-computed trust data for T045's GET
// /trust/:userId - it never recalculates a score itself (that stays
// TrustScoreRecalculationService's job, triggered only by a
// rating.submitted/report.filed event) and never writes to trust_scores.
// Splitting "read" from "recalculate" into two interfaces, rather than
// adding a getTrustData method onto TrustScoreRecalculationService, is also
// Interface Segregation (SOLID-I): TrustScoreController only needs a reader,
// so it only ever depends on an interface shaped like one.
//
// Dependency Inversion (SOLID-D): TrustScoreController depends on this
// interface, never on TrustScoreReadServiceImpl - same shape as every other
// controller-to-service seam in this service (RatingController ->
// RatingService).
public interface TrustScoreReadService {

    // Throws TrustScoreNotFoundException when no trust_scores row exists yet
    // for this userId - TrustScoreController's RestControllerAdvice
    // (RatingExceptionHandler) turns that into a 404, never a null return a
    // caller could forget to check.
    TrustScoreQueryResponse getTrustData(Long userId);
}
