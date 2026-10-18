package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
