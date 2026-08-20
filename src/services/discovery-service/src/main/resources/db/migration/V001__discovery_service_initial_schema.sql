-- V001__discovery_service_initial_schema.sql
--
-- Flyway naming convention: V{version}__{description}.sql, two underscores.
-- Flyway records a checksum of this file in its schema history table the
-- first time it runs; if the file's bytes ever change afterward, Flyway
-- refuses to start rather than silently re-running or skipping it. That is
-- the DDL equivalent of idempotency in a distributed system — "apply once,
-- and detect tampering/drift rather than guess".
--
-- Database per Service: this file only ever targets DiscoveryServiceDb (the
-- connection string Discovery Service is given). It has no knowledge of, and
-- no permission to reach, UserServiceDb/ConnectionServiceDb/etc. Discovery
-- finds out about users via the `user.verified` Kafka event and HTTP calls,
-- never a SQL join across service boundaries.

-- SQL Server requires six specific session settings to be in effect before
-- indexing a PERSISTED computed column like the `destination` geography column
-- below. JDBC connections (used by Flyway) default ARITHABORT to OFF, so these
-- must be set explicitly or the CREATE SPATIAL INDEX fails with error 1934 even
-- though manual sqlcmd verification passed. All six are required together.
SET ANSI_NULLS ON;
GO
SET ANSI_PADDING ON;
GO
SET ANSI_WARNINGS ON;
GO
SET ARITHABORT ON;
GO
SET CONCAT_NULL_YIELDS_NULL ON;
GO
SET NUMERIC_ROUNDABORT OFF;
GO
SET QUOTED_IDENTIFIER ON;
GO

-- Primary Keys: BIGINT IDENTITY, not UNIQUEIDENTIFIER/UUID. Every row here is
-- only ever written by this one service's own database — there's no
-- offline/multi-writer ID generation problem to solve, so a compact,
-- sequential integer (cheap clustered index, cheap FK storage) wins over a
-- UUID's randomness and storage cost.
CREATE TABLE scan_sessions (
    id                   BIGINT IDENTITY(1,1) PRIMARY KEY,
    user_id              BIGINT          NOT NULL,
    destination_lat      FLOAT           NOT NULL,
    destination_lng      FLOAT           NOT NULL,
    destination_label    NVARCHAR(200)   NULL,

    -- Geospatial Indexing: the raw lat/lng floats above are what the API
    -- accepts and returns, but "how far apart are two points on a sphere" is
    -- hard/slow to compute correctly in application code (it's not flat
    -- Euclidean distance — the earth curves). SQL Server's native `geography`
    -- type understands spherical distance natively, and a PERSISTED computed
    -- column keeps it automatically in sync with the lat/lng inputs instead
    -- of requiring every writer to remember to populate a second column.
    -- NOTE: destination_lat/lng are the traveler's STATED destination for
    -- this scan, not a live GPS fix — no real-time location is ever stored
    -- here. Conflating the two would break the platform's privacy contract.
    destination          AS geography::Point(destination_lat, destination_lng, 4326) PERSISTED,

    departure_time       DATETIME2       NULL,
    started_at           DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME(),
    ended_at             DATETIME2       NULL
);

-- Foreign Key access pattern: "find this user's scan sessions" is the
-- dominant query (e.g. "is this user currently scanning?"), so user_id gets
-- its own non-clustered index rather than relying on a full-table scan.
CREATE INDEX idx_scan_sessions_user_id ON scan_sessions (user_id);

-- Temporal columns indexed for "sessions still active" / cleanup sweeps.
CREATE INDEX idx_scan_sessions_started_at ON scan_sessions (started_at);
CREATE INDEX idx_scan_sessions_ended_at ON scan_sessions (ended_at);

-- Spatial Index: without this, "find travelers heading near point X" forces
-- SQL Server to compute geography::STDistance() against every single row —
-- an O(n) scan per query. A spatial index partitions the earth into a grid
-- (a quadtree-like tessellation) so a distance/proximity query can prune
-- almost the entire table before doing any real distance math, which is what
-- makes "1 km radius" discovery scans fast enough to run per-user, live.
CREATE SPATIAL INDEX idx_scan_sessions_destination
    ON scan_sessions (destination)
    USING GEOGRAPHY_AUTO_GRID;

CREATE TABLE ble_tokens (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    user_id      BIGINT          NOT NULL,

    -- We store a hash of the rotating BLE token, never the raw token, for
    -- the same reason passwords are hashed: the token itself is what gets
    -- broadcast over Bluetooth in the clear (see BLE offline mode's rotating
    -- short-lived token design) — anyone who intercepts the broadcast should
    -- not be able to reverse it back into something that matches a DB row
    -- and identifies a real user.
    token_hash   NVARCHAR(128)   NOT NULL,
    expires_at   DATETIME2       NOT NULL
);

CREATE INDEX idx_ble_tokens_user_id ON ble_tokens (user_id);

-- expires_at is indexed for the TTL-style cleanup job that purges rotated-out
-- tokens; without an index this degrades to a full scan as the table grows.
CREATE INDEX idx_ble_tokens_expires_at ON ble_tokens (expires_at);

-- token_hash is looked up by BLE scanners resolving a token they just heard
-- over the air back into "does this correspond to a live session" — indexed
-- for that lookup direction, separate from the user_id lookup direction.
CREATE INDEX idx_ble_tokens_token_hash ON ble_tokens (token_hash);
