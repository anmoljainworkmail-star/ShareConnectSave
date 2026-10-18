package com.shareconnectsave.rating.domain;

import java.time.Instant;
import java.util.List;

// Dependency Inversion + Interface Segregation (SOLID-D/I): TrustScoreCalculator
// (T043) depends on THIS minimal shape, not on the Rating JPA @Entity. Rating
// carries raterId/connectionId/a raw JSON `tags` String column (see Rating.java's
// own comment: deserializing that column to List<RatingTag> is explicitly a
// mapper/service-layer job, not the entity's). A pure scoring algorithm has no
// business knowing about raterId, connectionId, or JSON columns at all - it only
// ever needs "when did this happen" and "what tags did it carry". The future
// RatingService (T044) is the one place that reads Rating rows out of the
// database and maps each one into a RatingSnapshot before calling the
// calculator - persistence shape and scoring shape can now change independently
// of each other.
public record RatingSnapshot(Instant occurredAt, List<RatingTag> tags) {
}
