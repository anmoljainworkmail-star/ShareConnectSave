-- V002__ble_seeds.sql
--
-- Migration-never-edited convention (see V001's own header comment): V001
-- already shipped and Flyway locked its checksum into the schema history
-- table the first time it ran. This ticket (T024 v2) needs a schema change
-- for the SAME feature V001's ble_tokens table supported, but the correct
-- move is a brand new file, never editing V001's bytes after the fact.
--
-- Supersedes v1's ble_tokens (index-a-hash-then-look-it-up design). v2
-- (client-derived rotating tokens, mirroring Apple/Google's Exposure
-- Notification system) needs the server to retain each user's actual SEED,
-- not a hash of a server-minted token — resolving a submitted broadcast
-- value means recomputing HMAC-SHA256(seed, window) server-side, which is
-- impossible starting from a hash alone. ble_tokens was already designed to
-- hold nothing worth preserving (ephemeral by design), so it is dropped
-- outright rather than migrated row-by-row.
DROP TABLE ble_tokens;

CREATE TABLE ble_seeds (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    user_id      BIGINT          NOT NULL,

    -- NOT hashed, unlike v1's token_hash column — see this ticket's Security
    -- note: the Tech Lead has explicitly accepted the larger blast radius of
    -- storing the raw seed (same trade-off Apple/Google's own system accepts
    -- for a day's Temporary Exposure Key) and declined application-level
    -- encryption-at-rest for this column in this ticket. 32 raw random bytes,
    -- Base64url-encoded without padding, comfortably fits NVARCHAR(64).
    seed         NVARCHAR(64)    NOT NULL,

    created_at   DATETIME2       NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at   DATETIME2       NOT NULL
);

-- Same access pattern as v1's ble_tokens: BleTokenServiceImpl.issueSeed's
-- per-user cleanup (deleteByUserIdAndExpiresAtBefore) needs this to avoid a
-- full-table scan.
CREATE INDEX idx_ble_seeds_user_id ON ble_seeds (user_id);

-- expires_at indexed for both that same per-user cleanup and
-- BleTokenCleanupTask's service-wide @Scheduled sweep
-- (deleteByExpiresAtBefore) — without it, both degrade to a full scan as the
-- table grows.
CREATE INDEX idx_ble_seeds_expires_at ON ble_seeds (expires_at);
