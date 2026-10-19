package com.shareconnectsave.rating.exception;

// Same shape as connection-service's ConnectionNotFoundException and
// discovery-service's ScanSessionNotFoundException: a lookup by id (here, a
// trust_scores row for a given userId, T045's GET /trust/:userId) that finds
// nothing is an expected outcome - a user who has never been rated yet has
// no row at all (TrustScoreRecalculationService only ever upserts on a
// rating.submitted/report.filed event, T044) - not a bug. It earns its own
// stable 404 + error code instead of falling through to GlobalExceptionHandler's
// generic 500 catch-all.
public class TrustScoreNotFoundException extends RuntimeException {
    public TrustScoreNotFoundException(Long userId) {
        super("No trust score found for user " + userId);
    }
}
