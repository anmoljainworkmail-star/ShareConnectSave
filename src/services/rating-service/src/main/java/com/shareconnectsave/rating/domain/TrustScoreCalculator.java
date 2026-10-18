package com.shareconnectsave.rating.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Single Responsibility Principle (SOLID-S): this class does exactly one job -
// turn a user's rating history into a trust profile. No @Service annotation,
// no injected IRatingRepository/KafkaTemplate/HTTP client - trust scoring is
// pure math, so it is isolated from every I/O concern in the service. That
// isolation is what makes it testable with zero mocks (see
// TrustScoreCalculatorTest - `new TrustScoreCalculator()`, nothing else) and
// safe to change later without risking a DB/Kafka regression. The caller
// (RatingService, from T044 onward) owns fetching ratings/report counts and
// persisting the result - this class only ever transforms data it is handed.
//
// Dependency Inversion (SOLID-D): callers pass in List<RatingSnapshot>, a
// plain data shape this class defines - the calculator depends on nothing
// concrete (no Rating JPA entity, no repository abstraction), so it can be
// reused unchanged however that data eventually gets sourced (SQL today, a
// cache or read-replica later).
public class TrustScoreCalculator {

    // --- Recency window -----------------------------------------------
    // "Base score: weighted average of last 50 ratings" (T043 spec). Capping
    // the window means a user's score reflects recent behaviour, not a
    // lifetime average a thousand old five-star ratings could never let drop -
    // a user who was great for a year and then starts ghosting people should
    // see their score fall, not hide behind history.
    private static final int RECENT_RATINGS_WINDOW = 50;

    // --- Per-rating value scale -----------------------------------------
    // The `ratings` table (see Rating.java) stores only tags, never a 1-5
    // star number - so a single rating's numeric value has to be DERIVED from
    // its tags. The model here is "innocent until a red-flag tag says
    // otherwise": every rating starts at a perfect score and loses points
    // only for negative tags actually present.
    private static final double PERFECT_RATING_VALUE = 5.0;
    private static final double MIN_RATING_VALUE = 1.0;

    private static final double STANDARD_NEGATIVE_PENALTY = 1.0;

    // Ticket: "Negative tag multipliers: abusive and asked_for_money count
    // double." These two describe active harm (harassment, solicitation for
    // money), not mere inconvenience like a late arrival - they pull the
    // per-rating value down twice as hard as an ordinary negative tag.
    private static final double SEVERE_NEGATIVE_MULTIPLIER = 2.0;

    // Open/Closed Principle (SOLID-O): which tags count as negative - and
    // which of those are "severe" - is data held in these two sets, not a
    // chain of if/else comparisons sprinkled through the scoring method.
    // Adding a future negative tag to the vocabulary means adding it to one
    // of these EnumSets, not touching ratingValue()'s logic.
    private static final Set<RatingTag> NEGATIVE_TAGS = EnumSet.of(
            RatingTag.DIDNT_SHOW_UP,
            RatingTag.FAKE_DESTINATION,
            RatingTag.ABUSIVE,
            RatingTag.SPAM,
            RatingTag.ASKED_FOR_MONEY
    );

    private static final Set<RatingTag> SEVERE_NEGATIVE_TAGS = EnumSet.of(
            RatingTag.ABUSIVE,
            RatingTag.ASKED_FOR_MONEY
    );

    // --- Badge thresholds (ticket: "trusted (>= 4.5, >= 10 ratings)") -----
    private static final double TRUSTED_BADGE_MIN_SCORE = 4.5;
    private static final int TRUSTED_BADGE_MIN_RATINGS = 10;

    // --- Request limit thresholds -----------------------------------------
    // unlimited >= 3.5, throttled in [2.5, 3.5), suspended < 2.5 OR too many
    // reports. Expressed as the two boundary constants below plus the
    // guard-clause order in resolveRequestLimit().
    private static final double UNLIMITED_MIN_SCORE = 3.5;
    private static final double SUSPENDED_MAX_SCORE = 2.5;
    private static final int SUSPENSION_REPORT_THRESHOLD = 3;

    // New user, no rating history yet: give the benefit of the doubt rather
    // than defaulting to 0 (which would suspend every brand-new signup before
    // they ever travel). A perfect starting score still yields NONE badge
    // (badge also requires >= 10 ratings) and UNLIMITED request limit - the
    // platform trusts new users to send requests, it just won't show them as
    // "Trusted" until they have a track record.
    private static final double NEW_USER_DEFAULT_SCORE = PERFECT_RATING_VALUE;

    /**
     * Pure function: same ratingHistory + reportCount always produces the same
     * TrustScoreResult. Performs no I/O - the caller is responsible for
     * fetching both arguments and for persisting/publishing the result.
     */
    public TrustScoreResult calculate(List<RatingSnapshot> ratingHistory, long reportCount) {
        // Guard clause (early return) over nesting the whole method in an
        // if/else - a new user with no ratings is not a special case of the
        // weighted-average math, it is a different path entirely.
        if (ratingHistory == null || ratingHistory.isEmpty()) {
            return buildResult(NEW_USER_DEFAULT_SCORE, 0, reportCount);
        }

        double score = weightedAverageOfRecentWindow(ratingHistory);
        return buildResult(score, ratingHistory.size(), reportCount);
    }

    private TrustScoreResult buildResult(double score, int totalRatingCount, long reportCount) {
        BadgeLevel badgeLevel = resolveBadgeLevel(score, totalRatingCount);
        RequestLimit requestLimit = resolveRequestLimit(score, reportCount);
        return new TrustScoreResult(score, badgeLevel, requestLimit);
    }

    // "Weighted average of last 50 ratings (recent ratings weighted higher)":
    // 1. sort oldest -> newest and keep only the most recent
    //    RECENT_RATINGS_WINDOW entries (older history outside the window is
    //    dropped entirely, not just down-weighted).
    // 2. within that window, assign a linearly increasing weight by recency
    //    rank - the oldest rating still in the window gets weight 1, the
    //    newest gets weight N - so a bad week last month counts for less than
    //    a bad week yesterday.
    private double weightedAverageOfRecentWindow(List<RatingSnapshot> ratingHistory) {
        List<RatingSnapshot> oldestToNewest = new ArrayList<>(ratingHistory);
        oldestToNewest.sort(Comparator.comparing(RatingSnapshot::occurredAt));

        int windowStart = Math.max(0, oldestToNewest.size() - RECENT_RATINGS_WINDOW);
        List<RatingSnapshot> window = oldestToNewest.subList(windowStart, oldestToNewest.size());

        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (int index = 0; index < window.size(); index++) {
            double recencyWeight = index + 1; // oldest-in-window = 1 ... newest = window.size()
            weightedSum += ratingValue(window.get(index)) * recencyWeight;
            weightTotal += recencyWeight;
        }
        return weightedSum / weightTotal;
    }

    // Derives one rating's numeric value from its tags: start perfect, subtract
    // a penalty per negative tag present (doubled for the two severe tags),
    // never drop below MIN_RATING_VALUE. Positive tags (Friendly, On time,
    // etc.) have no additional numeric effect here - they are what keeps a
    // rating AT the perfect baseline rather than something that could push it
    // above 5.
    private double ratingValue(RatingSnapshot rating) {
        double value = PERFECT_RATING_VALUE;
        for (RatingTag tag : rating.tags()) {
            if (!NEGATIVE_TAGS.contains(tag)) {
                continue;
            }
            double multiplier = SEVERE_NEGATIVE_TAGS.contains(tag) ? SEVERE_NEGATIVE_MULTIPLIER : 1.0;
            value -= STANDARD_NEGATIVE_PENALTY * multiplier;
        }
        return Math.max(MIN_RATING_VALUE, value);
    }

    // Strategy pattern (via enum, see BadgeLevel.java's own comment): the
    // rule for "what counts as Trusted" lives entirely in these two
    // constants and this one comparison - a future "Super Trusted" tier is an
    // enum constant + a threshold here, not a rewrite of the calculator.
    private BadgeLevel resolveBadgeLevel(double score, int totalRatingCount) {
        if (score >= TRUSTED_BADGE_MIN_SCORE && totalRatingCount >= TRUSTED_BADGE_MIN_RATINGS) {
            return BadgeLevel.TRUSTED;
        }
        return BadgeLevel.NONE;
    }

    // Guard-clause order is deliberate, not arbitrary: the report-count check
    // runs FIRST because 3+ filed reports is a moderation signal (someone
    // actively flagged active harm) that must override whatever the
    // ratings-derived score still says - a high score built before the
    // reports came in should not keep a flagged user's requests flowing.
    private RequestLimit resolveRequestLimit(double score, long reportCount) {
        if (reportCount >= SUSPENSION_REPORT_THRESHOLD) {
            return RequestLimit.SUSPENDED;
        }
        if (score < SUSPENDED_MAX_SCORE) {
            return RequestLimit.SUSPENDED;
        }
        if (score < UNLIMITED_MIN_SCORE) {
            return RequestLimit.THROTTLED;
        }
        return RequestLimit.UNLIMITED;
    }
}
