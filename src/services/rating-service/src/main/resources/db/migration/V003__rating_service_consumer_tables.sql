-- V003__rating_service_consumer_tables.sql
--
-- T044: adds the tables Rating Service's first three Kafka consumers
-- (ChatClosedConsumer, RatingSubmittedConsumer, ReportFiledConsumer) need -
-- closed_chats (replaces PlaceholderChatClosureChecker's always-true stub
-- with a real data source) and processed_events (Idempotency ledger, shared
-- shape with connection-service's own V005__processed_events.sql).
--
-- Additive history, like V001/V002 before it - nothing here alters an
-- existing column's type or drops anything, it only adds new tables and one
-- new column to an existing table.

CREATE TABLE closed_chats (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,

    -- Logical reference to ConnectionServiceDb's connection_requests table -
    -- plain BIGINT, never a cross-database FOREIGN KEY, same Database per
    -- Service reasoning as ratings.connection_id in V001.
    connection_id   BIGINT          NOT NULL,
    user_a_id       BIGINT          NOT NULL,
    user_b_id       BIGINT          NOT NULL,

    created_at      DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Unique, not just indexed: ChatClosureChecker.isChatClosed and
-- ChatClosedConsumer's own redelivery guard both rely on "at most one row
-- per connection" to stay a simple existence check rather than needing to
-- reduce multiple rows down to one answer.
CREATE UNIQUE INDEX idx_closed_chats_connection_id ON closed_chats (connection_id);

CREATE TABLE processed_events (
    -- VARCHAR, not UNIQUEIDENTIFIER: every event_id on this platform's wire
    -- contracts is a UUID-shaped STRING (see RatingSubmittedEvent's own
    -- comment on why ids are never typed as a real UUID column here) - this
    -- table only ever needs byte-for-byte string equality, same convention
    -- as connection-service's own processed_events table.
    event_id        VARCHAR(36)     NOT NULL PRIMARY KEY,
    processed_at    DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME()
);

-- T044 addition to the ALREADY-SHIPPED trust_scores table (V001/T041): see
-- TrustScore.reportCount's own class comment for why this column exists -
-- RatingSubmittedConsumer needs to recalculate using the last report count
-- this service was ever told about, not an assumed zero, so that column has
-- to live somewhere durable. Added as a nullable-then-backfilled column
-- rather than editing V001 in place (unlike T041's own in-place
-- request_limit correction) because, by this ticket, V001/V002 may already
-- be applied to a real database with real rows - an ALTER in a new
-- migration is the only safe way to extend an already-live table.
ALTER TABLE trust_scores ADD report_count BIGINT NOT NULL CONSTRAINT df_trust_scores_report_count DEFAULT 0;
