package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Pattern: Repository - plain JpaRepository, no "I" prefix. The ticket text
// names this IRatingRepository, but this project's established convention
// (documented in CLAUDE.md's Java Rules and already applied to
// connection-service's own OutboxRepository, which was renamed away from
// IOutboxRepository to match) is that repository interfaces extending
// JpaRepository never take an "I" prefix - only non-repository service
// abstractions like IOutboxService do. Named RatingRepository here for
// consistency with that convention; see this ticket's final report for the
// explicit deviation note.
//
// existsByConnectionIdAndRaterId backs the "has this user already rated
// this connection" guard clause in RatingServiceImpl - a derived-query
// existence check, not a count fetched into Java and compared by hand, and
// not a try/catch around the unique index violation either (that index is
// still the last line of defense for a genuine race, same relationship as
// connection-service's own unique-index-as-backstop pattern).
public interface RatingRepository extends JpaRepository<Rating, Long> {

    boolean existsByConnectionIdAndRaterId(Long connectionId, Long raterId);

    // T044 addition: TrustScoreRecalculationServiceImpl's entire "ratings about
    // me" read - the full history TrustScoreCalculator needs is fetched here,
    // never recomputed from an in-memory running total, since the calculator
    // itself is a pure function with no state of its own (Single
    // Responsibility, SOLID-S) and this service owns its own `ratings` table
    // directly (Database per Service).
    List<Rating> findByRatedId(Long ratedId);
}
