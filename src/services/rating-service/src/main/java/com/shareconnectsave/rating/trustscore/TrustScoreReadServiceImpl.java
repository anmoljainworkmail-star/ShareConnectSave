package com.shareconnectsave.rating.trustscore;

import com.shareconnectsave.rating.RatingRepository;
import com.shareconnectsave.rating.TrustScoreRepository;
import com.shareconnectsave.rating.domain.BadgeLevel;
import com.shareconnectsave.rating.domain.TrustScore;
import com.shareconnectsave.rating.domain.TrustScoreQueryResponse;
import com.shareconnectsave.rating.exception.TrustScoreNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Single Responsibility (SOLID-S): see TrustScoreReadService's own comment -
// this class only reads trust_scores + counts ratings and maps the result
// into the wire DTO; it performs no scoring math and no writes.
//
// Dependency Inversion (SOLID-D): both repositories are injected as the
// interfaces Spring Data generates, not concrete implementations.
@Service
@RequiredArgsConstructor
public class TrustScoreReadServiceImpl implements TrustScoreReadService {

    private final TrustScoreRepository trustScoreRepository;
    private final RatingRepository ratingRepository;

    @Override
    public TrustScoreQueryResponse getTrustData(Long userId) {
        TrustScore trustScore = trustScoreRepository.findByUserId(userId)
                .orElseThrow(() -> new TrustScoreNotFoundException(userId));

        long ratingCount = ratingRepository.countByRatedId(userId);

        // isTrusted deliberately reuses the ALREADY-STORED badgeLevel rather
        // than re-comparing trustScore/ratingCount against the 4.5/10
        // thresholds here a second time. Those two numbers live in exactly
        // ONE place - TrustScoreCalculator.TRUSTED_BADGE_MIN_SCORE/
        // TRUSTED_BADGE_MIN_RATINGS (Strategy pattern, see BadgeLevel's own
        // comment) - and badgeLevel was computed from that same calculator
        // using this exact score + rating count at the last recalculation
        // (T044's TrustScoreRecalculationServiceImpl.recalculate runs on
        // every rating.submitted/report.filed event, so it is never stale
        // relative to the ratings this count query would re-read). Comparing
        // against hardcoded 4.5/10 constants again here would duplicate a
        // business rule this service already owns elsewhere - Open/Closed
        // (SOLID-O): if the thresholds ever change, TrustScoreCalculator is
        // still the only file that needs to change.
        boolean isTrusted = trustScore.getBadgeLevel() == BadgeLevel.TRUSTED;

        // Boundary mapping (same call T044's Kafka producer already makes,
        // see RequestLimit.wireValue()'s own comment): the ONE place this
        // enum ever becomes the plain integer Kafka and HTTP both contract
        // for, so the two can never silently disagree on what a category
        // means numerically.
        int requestLimit = trustScore.getRequestLimit().wireValue();

        return new TrustScoreQueryResponse(
                userId,
                trustScore.getScore(),
                requestLimit,
                isTrusted,
                (int) ratingCount);
    }
}
