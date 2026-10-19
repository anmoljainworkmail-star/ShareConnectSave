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

    // T044 addition (V003 migration): the last report_count_for_target this
    // service was ever told about for this user. TrustScoreCalculator.calculate
    // requires a reportCount on EVERY call, but a rating.submitted event never
    // carries one (Database per Service - Report Service alone owns that
    // number; Rating Service only learns it secondhand via report.filed). This
    // column is what lets RatingSubmittedConsumer recalculate using the most
    // recently known report count instead of silently assuming zero, which
    // would otherwise lift a report-driven suspension the next time any new
    // rating arrived. ReportFiledConsumer is the only writer of a fresh value;
    // RatingSubmittedConsumer only ever reads this column back unchanged.
    @Column(name = "report_count", nullable = false)
    private Long reportCount;

    // insertable/updatable = false: SQL Server's own
    // `DEFAULT SYSUTCDATETIME()` is the row's ground truth for "when was
    // this last written", not this app server's clock — matches Rating's
    // createdAt and discovery-service's ScanSession.startedAt.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    // Tell, Don't Ask (encapsulation) - same shape as OutboxEvent.markProcessed:
    // TrustScoreRecalculationServiceImpl asks this row to apply a freshly
    // computed result rather than reaching in with raw setters at its one call
    // site. Works identically whether this instance is a brand-new row (about
    // to be INSERTed) or an existing one being upserted - the caller does not
    // need to know which.
    public void applyRecalculation(BigDecimal score, BadgeLevel badgeLevel, RequestLimit requestLimit, Long reportCount) {
        this.score = score;
        this.badgeLevel = badgeLevel;
        this.requestLimit = requestLimit;
        this.reportCount = reportCount;
    }
}
