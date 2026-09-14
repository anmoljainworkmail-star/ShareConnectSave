# Review Follow-ups

Non-blocking findings surfaced during `/review-task` on an `APPROVED_WITH_MINOR_ISSUES`
verdict. These do not persist on their own — each entry names the downstream task(s)
whose implementation or ticket should pick it up.

## From T023 (GPS Discovery Query)

1. **Self-match risk if a user ever has two simultaneously-active scan sessions.**
   `scan_sessions` has no unique constraint on `user_id WHERE ended_at IS NULL` (only a
   non-unique index — see `V001__discovery_service_initial_schema.sql`), and
   `ScanSessionServiceImpl.startScan` never checks for an existing active session before
   inserting a new row. `ScanQueryServiceImpl.findNearby` excludes the caller only by
   session id (`candidateSessionId.equals(callerSessionId)`), never by
   `candidateSession.getUserId().equals(callerUserId)`. If a client double-fires
   `POST /scan/start` (double-tap, retry-after-timeout), the user's *other* active session
   has identical location/destination/departure-time and passes every filter — the user
   would see themselves as a "nearby match."
   Fix options: enforce single-active-session-per-user at write time (unique filtered
   index or a check in `startScan`), and/or add an explicit `userId`-based exclusion
   predicate in `findNearby` as defense-in-depth regardless of the upstream fix.
   Affects: T022, T023.

2. **`stopScan`'s Redis mutations execute inside the still-open SQL transaction and are
   not rolled back if the SQL commit later fails.** `ScanSessionServiceImpl.stopScan` runs
   the Redis `DELETE`/`SREM` calls under the same `@Transactional` boundary that holds the
   SQL pessimistic lock, but Redis has no visibility into that transaction — if the SQL
   commit fails after the Redis calls already succeeded, the session row rolls back to
   "active" in SQL while its Redis keys (`scan:active_sessions` membership,
   location/gender/women_only keys) are already gone. Low-probability (all the Redis ops
   are idempotent, so a client retry heals it), but a real correctness gap: until retried,
   the session is a "ghost active" row invisible to `/scan/nearby` but still billed active
   in SQL.
   Fix: move the Redis mutations to run only after the SQL transaction actually commits
   (e.g. `@TransactionalEventListener(phase = AFTER_COMMIT)`), or centralize as part of
   the Redis consolidation work.
   Affects: T022, T026.

3. **Manual DTO construction instead of MapStruct.** `UserServiceClientImpl.getUserCard`
   hand-builds `UserCardResponse` from `PublicUserProfileResponse` field-by-field rather
   than using a MapStruct mapper, which the `java-spring-boot` skill states should always
   be used for DTO translation ("never write mappers by hand"). Low risk at 4 fields plus
   one computed value, but worth converting before this shape gets copy-pasted again.
   Affects: T023, T024 (BLE resolve endpoint is documented to reuse this same
   client/DTO shape).

4. **The `destination` geography column + spatial index (T006/T022) are unused by
   `ScanQueryServiceImpl.findNearby`, and using them isn't a free win.** T006's migration
   comment states the spatial index exists so "find travelers heading near point X" avoids
   an O(n) `geography::STDistance()` scan per row. But `findNearby`'s two geographic filters
   don't map onto what that index accelerates:
   - The radius filter compares LIVE position to LIVE position — this can never use the
     `destination` geography column, because live GPS is Redis-only by T022's own Location
     Privacy rule (never persisted to SQL, not even in a TTL'd row). No query rewrite fixes
     this; the data the index would need is deliberately never in SQL.
   - The route-overlap filter computes *bearing* (direction from the caller's live position
     toward each destination) — a directional question, not a "how far apart are two points"
     question, so `STDistance` doesn't answer it either. Bearing math has to stay in the app.
   - The one place the index COULD help: pre-filtering candidates by destination-to-destination
     proximity before the radius-narrowed `findAllById` call (see `ScanQueryServiceImpl` —
     candidates are now narrowed by live-position radius via Redis BEFORE the SQL fetch, so
     any destination-based pre-filter would need to compose with that, not replace it). But this is
     a real correctness tradeoff, not a pure optimization — two travelers heading the same
     direction can have destinations far apart (one going further down the same road) and
     still deserve a high bearing-overlap score. A destination-distance pre-filter with too
     tight a threshold would silently exclude real matches; too generous a threshold barely
     prunes anything, defeating the point.
   **Decision (made after this ticket shipped): keep the column and index, unused, for now.**
   Two reasons: (a) it remains a live option for the destination-proximity pre-filter above —
   not yet decided whether to build it, or what threshold would avoid excluding real
   same-direction-but-farther-apart matches; (b) this project's explicit purpose is learning
   these concepts, and a correctly-built, if currently-idle, SQL Server spatial index is a
   real worked example worth keeping visible rather than optimizing away. Documented in
   `ScanSession.java`'s entity comment rather than in `V001__discovery_service_initial_schema.sql`
   itself — that migration is Flyway-checksummed and, per this project's own convention,
   never edited after it may have shipped; if the pre-filter is ever built or the column is
   ever dropped for good, do it via a new migration, not by editing V001.
   Affects: T023, T026 (Redis Caching Layer — already touches this query path's caching,
   natural place to revisit the enumeration/pre-filter strategy together, if ever revisited).
