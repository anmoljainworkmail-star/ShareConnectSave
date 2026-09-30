-- V005__processed_events.sql
--
-- Idempotency (kafka-outbox skill): Connection Service has been a Kafka
-- PRODUCER since T032 (outbox -> connection.accepted / connection.expired)
-- but never a CONSUMER until T033's trust.score.updated listener. Kafka's
-- at-least-once delivery guarantee means the same event_id can be handed to
-- this consumer group twice (e.g. this JVM crashes after updating
-- RequestLimitCache but before the container commits the offset) — this
-- table is the dedup ledger the listener checks before acting, turning a
-- redelivery into a no-op read instead of a double cache write.
--
-- Same shape as discovery-service's V003__processed_events.sql byte-for-byte
-- (event_id NVARCHAR(36) PK, DB-assigned processed_at) — every consumer in
-- this platform uses one idempotency table with this exact column set, per
-- the kafka-outbox skill; this is NOT a second, differently-shaped
-- idempotency mechanism, just this service's own copy of the same table
-- (Database per Service — each service owns its schema, so "reuse" means
-- "same convention", not "same physical table").
CREATE TABLE processed_events (
    -- Stored as the producer's original string, not SQL Server's
    -- UNIQUEIDENTIFIER type: event_id really is a UUID from every producer
    -- today (Rating Service, once Phase 6 ships it), but this column only
    -- ever needs equality lookups, never a UUID-specific SQL function — a
    -- plain NVARCHAR sidesteps any Hibernate<->SQL Server UUID JDBC-type
    -- surprise, the same class of mismatch application.yml's
    -- preferred_instant_jdbc_type override already exists to avoid for
    -- Instant/DATETIME2.
    event_id     NVARCHAR(36)  NOT NULL PRIMARY KEY,

    -- DB-assigned, not app-assigned (same convention as
    -- connection_requests.created_at): reflects the moment the row actually
    -- committed, not the moment this JVM built the object in memory.
    processed_at DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Pruning (kafka-outbox skill): a scheduled job (ProcessedEventsPruner,
-- added alongside this migration) deletes rows older than 7 days, safe
-- because Kafka's message retention is also 7 days — any redelivery would
-- happen within that window. Indexed on processed_at alone so that job's
-- "WHERE processed_at < @cutoff" predicate can seek instead of scanning the
-- whole table as it grows.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
