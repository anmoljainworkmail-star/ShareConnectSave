-- V001__rating_service_initial_schema.sql
--
-- Flyway naming convention: V{version}__{description}.sql. Flyway checksums
-- this file on first apply; changing it afterward breaks the checksum check
-- on purpose, forcing a new migration file instead of an in-place edit —
-- schema changes are additive history, like commits, not mutable state.
--
-- Database per Service: this file only ever targets RatingServiceDb.
-- Discovery Service reacts to trust score changes via the
-- `trust.score.updated` Kafka event, not by querying this table directly.
CREATE TABLE ratings (
    -- Primary Keys: BIGINT IDENTITY over UUID — single-database record, no
    -- need for globally-unique offline-generated IDs.
    id             BIGINT IDENTITY(1,1) PRIMARY KEY,

    rater_id       BIGINT          NOT NULL,
    rated_id       BIGINT          NOT NULL,

    -- connection_id ties a rating back to the specific ConnectionServiceDb
    -- request that produced it, but it is stored as a plain BIGINT, not a
    -- FOREIGN KEY REFERENCES into another database. SQL Server cross-database
    -- foreign keys are technically possible but reintroduce exactly the
    -- coupling "Database per Service" exists to remove: a rating write would
    -- fail if Connection Service's DB were briefly unreachable. The
    -- reference is logical, validated at the application layer instead.
    connection_id  BIGINT          NOT NULL,

    -- Open/Closed Principle: tags are stored as a JSON array, not one column
    -- per tag or a normalized rating_tags join table. Adding a new tag
    -- ("Great conversation", "On time", ...) becomes an application-level
    -- config/enum change — zero schema migrations required. SQL Server
    -- validates that this column contains well-formed JSON (2016+), which is
    -- enough structural safety without forcing a rigid relational shape onto
    -- a value list that's expected to grow.
    tags           NVARCHAR(MAX)   NULL
        CONSTRAINT ck_ratings_tags_is_json CHECK (tags IS NULL OR ISJSON(tags) = 1),

    created_at     DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Foreign Key access pattern: "ratings I gave" and "ratings about me" are
-- both real query shapes (the latter feeds trust score recalculation), so
-- both directions get their own index.
CREATE INDEX idx_ratings_rater_id ON ratings (rater_id);
CREATE INDEX idx_ratings_rated_id ON ratings (rated_id);
CREATE INDEX idx_ratings_connection_id ON ratings (connection_id);
CREATE INDEX idx_ratings_created_at ON ratings (created_at);

-- Concurrency guard: a rater rates a given connection at most once. Without
-- this, a double-tap or client retry on the same connection produces two
-- rating rows for the same interaction, double-counting it in trust score
-- recalculation. Unique, not just indexed — the DB is the only thing that
-- can enforce this atomically across two racing INSERTs.
CREATE UNIQUE INDEX idx_ratings_connection_id_rater_id ON ratings (connection_id, rater_id);

CREATE TABLE trust_scores (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,

    -- One row per user. Single Responsibility: TrustScoreCalculator (service
    -- code) only computes the number — this table only stores the current,
    -- already-computed snapshot. Recomputing a score never requires
    -- rewriting rating history, only upserting this one row.
    user_id         BIGINT          NOT NULL,

    score           DECIMAL(5,2)    NOT NULL DEFAULT 0,
    badge_level     NVARCHAR(20)    NOT NULL DEFAULT 'NONE',
    request_limit   INT             NOT NULL DEFAULT 0,
    updated_at      DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Unique, not just indexed: a user has exactly one current trust score row.
-- This is what makes "recalculate on rating.submitted" an upsert instead of
-- an ever-growing history table that every reader has to reduce themselves.
CREATE UNIQUE INDEX idx_trust_scores_user_id ON trust_scores (user_id);

-- badge_level drives visibility/throttling decisions in Discovery Service
-- (via trust.score.updated), so it's queried/filtered on directly.
CREATE INDEX idx_trust_scores_badge_level ON trust_scores (badge_level);
CREATE INDEX idx_trust_scores_updated_at ON trust_scores (updated_at);
