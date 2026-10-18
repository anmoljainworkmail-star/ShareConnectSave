package com.shareconnectsave.rating.domain;

// Immutable value object: a record, not a @Builder/@Getter class with setters.
// TrustScoreCalculator (T043) is a pure function - "same input always produces
// the same output" only means something if the output itself can never be
// mutated out from under a caller after the fact. score stays a plain double
// per the ticket spec; TrustScore (the JPA entity, T041) stores it as a
// BigDecimal - that persistence-precision conversion belongs to whichever
// service layer persists this result (T044), not to this pure calculation
// output, same boundary-conversion split as RequestLimit.wireValue() below.
public record TrustScoreResult(double score, BadgeLevel badgeLevel, RequestLimit requestLimit) {
}
