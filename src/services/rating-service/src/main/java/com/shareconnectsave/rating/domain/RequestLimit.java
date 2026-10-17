package com.shareconnectsave.rating.domain;

// Open/Closed Principle (SOLID-O): same reasoning as BadgeLevel — this is a
// category of throttling behaviour, not a raw numeric cap, and it is stored
// as a string (@Enumerated(EnumType.STRING) on TrustScore.requestLimit), not
// an INT. What a category like THROTTLED actually allows per day is business
// logic owned by Rating Service itself (T043's TrustScoreCalculator), not a
// number baked into this schema — that indirection is exactly what makes
// "change what THROTTLED means" a code-only change instead of a migration.
//
// Boundary translation (Tech Lead decision, 2026-10-17): this enum never
// leaves Rating Service as a string. The Kafka `trust.score.updated` event
// (contracts/kafka/trust.score.updated.schema.json) and the
// `GET /trust/:userId` HTTP response (contracts/openapi/rating-service.yaml)
// both already declare request_limit as a plain integer, and
// discovery-service (T025) / connection-service (T030, T033) already ship
// against that integer contract. Rather than retrofitting those shipped
// consumers to parse a string category, T044's Kafka producer and T045's
// HTTP endpoint each convert this enum to the matching integer wire value
// (UNLIMITED -> Integer.MAX_VALUE, THROTTLED -> 3, SUSPENDED -> 0) at the
// point the value crosses the service boundary. See T043/T044/T045 in
// SPECS.md for the full mapping contract.
public enum RequestLimit {
    UNLIMITED,
    THROTTLED,
    SUSPENDED
}
