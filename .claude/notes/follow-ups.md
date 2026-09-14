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

## From T024 (BLE Token Generation)

1. **`MissingRequestHeaderException` on a missing `X-User-Id` returns a raw 500, not a
   clean 400.** The shared `GlobalExceptionHandler` (shared-java-lib) only has handlers for
   `MethodArgumentNotValidException` (body validation) and a catch-all `Exception` (→
   `500 INTERNAL_ERROR`). Every endpoint in every Java service that reads `X-User-Id` via
   `@RequestHeader` — without a default — falls through to the catch-all if the gateway
   somehow forwards a request without it (this ticket's own `POST /scan/ble/token` and
   `POST /scan/ble/resolve` both do; so does `ScanController`). Confirmed during T024 review:
   the same gap was already present for `/scan/ble/token`, cycle 1 flagged it as
   pre-existing, and cycle 2 confirmed `/scan/ble/resolve` (made to require the header by
   this ticket's own block-list fix) hits the identical path. Fix: add
   `@ExceptionHandler(MissingRequestHeaderException.class)` to `GlobalExceptionHandler`
   returning `400 VALIDATION_ERROR`, once, in the shared library — not per-service.
   Affects: T091 (next ticket to touch shared-java-lib directly), and any future Java
   service controller reading trusted identity headers (T029, T041, T051, T056).

2. **No self-exclusion check in BLE resolve.** Unlike GPS discovery (`ScanQueryServiceImpl`
   excludes the caller's own session id before returning candidates), `BleTokenServiceImpl
   .resolveTokens` has no code path preventing a user from resolving a BLE token they
   themselves issued — if a client somehow collected its own broadcast, the service would
   return the caller's own profile card. Noted as a cosmetic observation, not a security
   defect (a user's own profile isn't sensitive to themselves), but worth a guard clause
   if this ever surfaces as a confusing UI case (a user "discovering" themselves).
   Affects: T065 (BLE Mode, Angular — the frontend ticket most likely to actually notice
   and need to handle this if it happens).

### From T024 v2 rework (client-derived rotating seed redesign)

3. **`BleSeedResponse` is a Java record, so it gets an auto-generated `toString()` that
   includes the raw seed value.** Unlike `BleSeed` (the JPA entity, correctly annotated
   `@Getter`-only — no `@Data`, so no auto `toString()`), the response DTO has no such
   guard, and a record's default `toString()` prints every field's value. Nothing in the
   codebase calls `.toString()` on it today (no request/response logging filter exists in
   this service), so there's no active leak — but it's a landmine: the first debug log,
   actuator httptrace-style filter, or logging library (e.g. logbook) added later will
   print the seed verbatim. Fix: override `toString()` on `BleSeedResponse` to redact the
   seed field, matching the caution already correctly applied to the entity.
   Affects: T024 itself (cheap enough to just fix directly next time this file is
   touched), and any future ticket that adds request/response logging to any Java service.

4. **`ble.seed.hmac-secret` (env var `BLE_TOKEN_HMAC_SECRET`) is a required startup
   property with no actual effect on token derivation.** This is intentional and
   Tech-Lead-approved (the client only ever holds the seed, never a server config value,
   so using this as part of the HMAC key would make client and server permanently unable
   to agree) — documented in code comments in `BleProperties.java` and `application.yml`.
   The gap: an SRE managing env vars in production will see a variable literally named
   `BLE_TOKEN_HMAC_SECRET`, reasonably assume it's load-bearing security material, and may
   rotate/manage it as such for zero actual effect — the in-code comment explaining this
   is invisible from a `.env`/Helm-values file. Fix: add a one-time startup log
   (`log.warn`/`log.info`) noting the property is bound but not used in derivation.
   Affects: whichever future ticket first adds real ops/deployment tooling for Discovery
   Service (no specific task ID yet in PROGRESS.md — likely bundled with T028, Discovery
   Service Docker Image, or a future ops-hardening pass).

5. **Expired-seed filtering happens in Java after the SQL fetch, not at the SQL level.**
   `BleTokenServiceImpl`'s candidate-seed lookup (`bleSeedRepository.findByUserIdIn`) has
   no `expires_at` predicate — an expired seed is still fetched across the wire, then
   filtered out in a Java stream before any HMAC comparison. Functionally correct (an
   expired seed can never match), purely a minor efficiency note. Fix: add a
   `findByUserIdInAndExpiresAtAfter(List<Long>, Instant)` derived query method instead.
   Affects: T024 itself if ever revisited, or T026 (Redis Caching Layer — same "query path
   efficiency" territory as its own scope).

6. **Broadcast-value comparison uses plain `String.equals`, not constant-time comparison.**
   A theoretical timing side-channel on the HMAC-derived value comparison in
   `BleTokenServiceImpl`'s resolve loop. Not called out by the ticket, and consistent with
   v1's own exact-match approach — flagged for awareness, not because it's currently
   considered in-scope for this feature's threat model (an attacker would need to measure
   sub-millisecond server response timing over a network to exploit it, against a value
   that already changes every 5 minutes). Fix, if ever prioritized: decode both sides to
   bytes and compare via `MessageDigest.isEqual`.
   Affects: no specific future task — revisit only if this service's threat model is
   ever formally reassessed (e.g. alongside T027, Resilience4j Circuit Breaker's own
   hardening pass).
