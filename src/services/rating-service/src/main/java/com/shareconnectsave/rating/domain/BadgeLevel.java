package com.shareconnectsave.rating.domain;

// Open/Closed Principle (SOLID-O): what counts as a valid badge level is a
// code change in this enum, not a SQL CHECK constraint enumerating allowed
// strings in V001__rating_service_initial_schema.sql. The badge_level column
// is a plain NVARCHAR(20) — adding a future badge (e.g. a "SUPER_TRUSTED"
// tier) later only means adding a constant here and a migration to widen the
// column if needed, never touching a CHECK constraint or the validator that
// reads this value. TrustScore.badgeLevel maps this with
// @Enumerated(EnumType.STRING), so the database stores the readable name
// ("TRUSTED") rather than an ordinal int — renumbering/reordering these
// constants later can never silently corrupt already-stored rows the way an
// ordinal mapping would.
public enum BadgeLevel {
    NONE,
    TRUSTED
}
