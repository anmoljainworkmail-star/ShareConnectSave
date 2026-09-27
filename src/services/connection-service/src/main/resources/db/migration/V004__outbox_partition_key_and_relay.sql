-- V004__outbox_partition_key_and_relay.sql
--
-- Flyway naming convention: V{version}__{description}.sql, same rule as
-- V001/V002/V003 -- this file is checksummed into Flyway's history table on
-- first run and must never be edited after that point; a further fix
-- belongs in a new V005, not here.
--
-- T032 wires the outbox table (created empty in V002, write-side only, no
-- relay yet) up to a real OutboxRelay -> KafkaTemplate. Two things V002's
-- shape was missing for that:
--
--   1. partition_key -- Kafka only guarantees message ordering WITHIN a
--      partition, and the partition a message lands on is derived from this
--      key (kafka-outbox skill: "every producer must key each event by its
--      aggregate id, never leave the key null"). V002 had no column for it
--      because nothing published to Kafka yet.
--
--   2. processed_at -- OutboxRelay needs to record WHEN a row was
--      successfully handed to Kafka, not just THAT it was (the status
--      column alone already captured PENDING/PROCESSED).
--
-- This also switches the primary key from a BIGINT IDENTITY to an
-- app-assigned UNIQUEIDENTIFIER: Idempotency (groundwork) needs this row's
-- own id to double as the published event's event_id for downstream
-- consumer dedup, which only works if the id is known BEFORE the row is
-- inserted (OutboxServiceImpl.publish mints it with UUID.randomUUID()) -- an
-- IDENTITY value is only assigned by SQL Server at INSERT time, too late to
-- reuse. There is no production data in this table yet (T032 is the first
-- ticket to make anything actually flow through it end-to-end), so
-- DROP + CREATE is the simplest correct migration here rather than an
-- in-place ALTER of the primary key's type.
DROP TABLE outbox;

CREATE TABLE outbox (
    id             UNIQUEIDENTIFIER   NOT NULL PRIMARY KEY,

    topic          NVARCHAR(255)      NOT NULL,

    -- Kafka's per-partition ordering guarantee is only as good as this
    -- value: every producer in ConnectionServiceImpl passes its
    -- connection_id here, never null and never derived inside the relay.
    partition_key  NVARCHAR(255)      NOT NULL,

    -- NVARCHAR(MAX): payload is an opaque, already-serialized JSON string
    -- (see OutboxEvent's own comment) -- this table never needs to query
    -- into individual fields of it, so there is no reason to model its
    -- structure in DDL.
    payload        NVARCHAR(MAX)      NOT NULL,

    -- Same Open/Closed reasoning as connection_requests.status in V001:
    -- plain NVARCHAR, not a CHECK constraint, so a future OutboxStatus
    -- value (e.g. DLQ, per the kafka-outbox skill) is a code change, not a
    -- schema migration.
    status         NVARCHAR(20)       NOT NULL DEFAULT 'PENDING',

    created_at     DATETIME2          NOT NULL DEFAULT SYSUTCDATETIME(),

    -- NULL until OutboxRelay successfully hands this row to Kafka.
    processed_at   DATETIME2          NULL
);

-- OutboxRelay's entire query shape is "give me PENDING rows, oldest first" --
-- indexed on (status, created_at) together so that query can seek straight
-- to the rows it wants instead of scanning every row and sorting in memory.
CREATE INDEX idx_outbox_status_created_at ON outbox (status, created_at);
