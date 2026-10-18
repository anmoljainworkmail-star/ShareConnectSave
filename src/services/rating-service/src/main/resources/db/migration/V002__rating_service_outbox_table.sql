-- V002__rating_service_outbox_table.sql
--
-- Outbox Pattern: this table is the durable staging area between "a rating
-- was persisted" and "rating.submitted was published to Kafka". A row here
-- commits in the SAME transaction as the ratings INSERT (see
-- RatingServiceImpl.submitRating) - either both commit, or neither does.
-- OutboxRelay polls this table on a fixed schedule and is the only
-- component in rating-service allowed to touch Confluent.Kafka/KafkaTemplate.
--
-- Same shape as connection-service's own outbox table
-- (V002__outbox_and_saga_state.sql + V004__outbox_partition_key_and_relay.sql),
-- built fresh here in one migration rather than two since rating-service has
-- no earlier outbox table to widen.
CREATE TABLE outbox (
    -- UNIQUEIDENTIFIER, app-assigned (no IDENTITY/DEFAULT NEWID()):
    -- OutboxServiceImpl mints this id itself, reusing the same UUID that is
    -- already embedded in the event payload's own event_id field, so one
    -- value does double duty as both the Kafka envelope's dedup key
    -- (Idempotency) and this row's primary key.
    id              UNIQUEIDENTIFIER PRIMARY KEY,

    topic           NVARCHAR(100)   NOT NULL,

    -- Kafka ordering is only guaranteed WITHIN a partition, and the
    -- partition a message lands on is derived from this key - every
    -- producer in this service keys by connection_id (the aggregate the
    -- rating.submitted event is about), matching connection-service's own
    -- "partition by the aggregate the event describes" convention.
    partition_key   NVARCHAR(100)   NOT NULL,

    -- JSON string, not a structured column: OutboxRelay only ever hands
    -- this value, byte-for-byte, to KafkaTemplate.send() - it never queries
    -- into individual fields of it.
    payload         NVARCHAR(MAX)   NOT NULL,

    -- Open/Closed Principle: a plain NVARCHAR, not a CHECK-constrained
    -- enumerated column, so OutboxStatus can gain a future constant (e.g.
    -- DLQ) without a schema migration.
    status          NVARCHAR(20)    NOT NULL DEFAULT 'PENDING',

    created_at      DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME(),
    processed_at    DATETIME2       NULL
);

-- Backs OutboxRelay's poll query: oldest-PENDING-first, scoped to PENDING
-- rows only, is the entire access pattern this table ever serves.
CREATE INDEX idx_outbox_status_created_at ON outbox (status, created_at);
