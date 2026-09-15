-- V003__processed_events.sql
--
-- Idempotency (kafka-outbox skill): Kafka's at-least-once delivery means the
-- T025 consumers for user.verified / trust.score.updated can see the exact
-- same event_id twice — e.g. this service applies the Redis side effect,
-- then crashes before the container commits the offset. The next poll
-- redelivers the same record. This table is the dedup ledger both listeners
-- check before acting: "has this event_id already been processed" turns a
-- redelivery into a no-op read instead of a double SADD / double trust-cache
-- write.
CREATE TABLE processed_events (
    -- Stored as the producer's original string, not SQL Server's
    -- UNIQUEIDENTIFIER type: event_id really is a UUID from every producer
    -- today (User Service's Guid.NewGuid()), but this column only ever needs
    -- equality lookups, never a UUID-specific SQL function — a plain
    -- NVARCHAR sidesteps any Hibernate<->SQL Server UUID JDBC-type surprise,
    -- the same class of mismatch application.yml's preferred_instant_jdbc_type
    -- override already exists to avoid for Instant/DATETIME2.
    event_id     NVARCHAR(36)  NOT NULL PRIMARY KEY,

    -- DB-assigned, not app-assigned (same convention as scan_sessions.started_at):
    -- reflects the moment the row actually committed, not the moment this JVM
    -- built the object in memory.
    processed_at DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
