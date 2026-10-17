package com.shareconnectsave.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

// Maps onto the trust_scores table from
// V001__rating_service_initial_schema.sql (T006/T041). One row per user —
// Single Responsibility (SOLID-S): this class is only a data container for
// the CURRENT, already-computed snapshot. The actual scoring algorithm lives
// in TrustScoreCalculator (a later Phase 7 ticket), which stays a pure
// function with no knowledge of JPA/Hibernate — recomputing a score never
// requires touching this entity's shape, only the calculator's logic.
@Entity
@Table(name = "trust_scores")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Logical reference to UserServiceDb's `users` table — see Rating's own
    // comment on raterId/ratedId for why this is a plain BIGINT, not a
    // physical FK, under Database per Service.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "score", nullable = false)
    private BigDecimal score;

    // Open/Closed Principle (SOLID-O): stored via @Enumerated(EnumType.STRING),
    // never EnumType.ORDINAL. ORDINAL would persist BadgeLevel.TRUSTED as the
    // integer 1 — silently wrong (and undetectable by ddl-auto: validate,
    // since it only checks column TYPE, not which values are valid) the
    // moment someone reorders or inserts a new constant into BadgeLevel.
    // STRING persists the literal name ("TRUSTED"), so the column is both
    // human-readable in a raw SQL query and immune to enum reordering.
    @Enumerated(EnumType.STRING)
    @Column(name = "badge_level", nullable = false, length = 20)
    private BadgeLevel badgeLevel;

    // Same STRING-not-ORDINAL reasoning as badgeLevel above, and the same
    // Open/Closed intent CLAUDE.md calls out explicitly: request_limit is a
    // throttling CATEGORY (UNLIMITED/THROTTLED/SUSPENDED), not a raw request
    // count, so it is a string column here — never an INT.
    @Enumerated(EnumType.STRING)
    @Column(name = "request_limit", nullable = false, length = 20)
    private RequestLimit requestLimit;

    // insertable/updatable = false: SQL Server's own
    // `DEFAULT SYSUTCDATETIME()` is the row's ground truth for "when was
    // this last written", not this app server's clock — matches Rating's
    // createdAt and discovery-service's ScanSession.startedAt.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
