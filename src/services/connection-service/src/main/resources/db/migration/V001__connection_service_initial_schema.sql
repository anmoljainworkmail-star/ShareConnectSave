-- V001__connection_service_initial_schema.sql
--
-- Flyway naming convention: V{version}__{description}.sql. Flyway checksums
-- this file into its history table on first run; editing it after the fact
-- makes every future migration attempt fail loudly instead of silently
-- re-applying or skipping a changed script. Idempotency here means "this
-- exact DDL runs at most once, ever, per database".
--
-- Database per Service: this table lives only in ConnectionServiceDb. The
-- Connection Lifecycle Saga (accept -> open chat -> met -> rate) coordinates
-- with Chat, Rating, and Discovery purely through Kafka events
-- (connection.accepted, connection.chat-failed, chat.closed) — never through
-- a query that reaches into another service's database.
CREATE TABLE connection_requests (
    -- Primary Keys: BIGINT IDENTITY over UUID. Single-database record, no
    -- offline/multi-node ID generation requirement, so the smaller, ordered,
    -- clustered-index-friendly integer is the simpler and cheaper choice.
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,

    requester_id  BIGINT          NOT NULL,
    recipient_id  BIGINT          NOT NULL,

    -- Status lifecycle column deliberately typed as plain NVARCHAR, not a SQL
    -- CHECK constraint enumerating allowed values. Open/Closed Principle at
    -- the schema level: what counts as a valid status (PENDING, ACCEPTED,
    -- EXPIRED, ...) is business logic that lives — and changes — in service
    -- code, not in DDL. Baking the value list into the schema would mean a
    -- new status requires a migration instead of a code change.
    status        NVARCHAR(20)    NOT NULL DEFAULT 'PENDING',

    -- Temporal Columns: DATETIME2 (not DATETIME) in UTC. created_at/updated_at
    -- track the record's own lifecycle; expires_at is the TTL boundary that
    -- drives the compensating-action side of the saga — a background sweep
    -- reverts any request still PENDING past its expiry back out of the flow.
    created_at    DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at    DATETIME2       NOT NULL,
    updated_at    DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Foreign Key access pattern: two directions of lookup matter equally here —
-- "requests I sent" (requester_id) and "requests sent to me" (recipient_id) —
-- so both get their own index rather than assuming one direction dominates.
CREATE INDEX idx_connection_requests_requester_id ON connection_requests (requester_id);
CREATE INDEX idx_connection_requests_recipient_id ON connection_requests (recipient_id);

-- Status is queried constantly (e.g. "show my PENDING requests", "sweep
-- everything still PENDING past expiry") — indexed on its own, and again
-- combined with expires_at below for the TTL sweep's exact predicate shape.
CREATE INDEX idx_connection_requests_status ON connection_requests (status);
CREATE INDEX idx_connection_requests_created_at ON connection_requests (created_at);

-- Composite index matches the TTL sweep query ("WHERE status = 'PENDING' AND
-- expires_at < @now") exactly, so SQL Server can seek instead of scan even as
-- the table accumulates history for requests that already resolved.
CREATE INDEX idx_connection_requests_status_expires_at
    ON connection_requests (status, expires_at);

-- Concurrency guard: at most one PENDING request per (requester, recipient) pair.
-- Filtered unique index — only enforces uniqueness among currently-PENDING rows,
-- so old EXPIRED/ACCEPTED history doesn't block a legitimate future re-request.
CREATE UNIQUE INDEX idx_connection_requests_unique_pending
    ON connection_requests (requester_id, recipient_id)
    WHERE status = 'PENDING';
