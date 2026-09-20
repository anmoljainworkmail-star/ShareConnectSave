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

## From T025 (Kafka Consumer: user.verified + trust.score.updated)

1. **TOCTOU race between the caller's eligibility check and session creation in
   `startScan`.** `ScanSessionServiceImpl.startScan` reads
   `eligibilityCacheService.isEligible(userId)` once at the top (the new guard clause),
   then proceeds to create the SQL row and Redis memberships. If a `trust.score.updated`
   suspension for that same user arrives on the Kafka consumer thread between that read
   and the writes, the now-suspended user still gets a session created — the check-then-act
   gap is unguarded by any lock across the Redis read and the SQL/Redis writes. Bounded
   exposure: the created session immediately fails every subsequent per-candidate
   eligibility check in `findNearby` (`ScanQueryServiceImpl.java:203`), so the window is
   "briefly held an active session," not "permanently visible to others." Consistent with
   every other cache-aside consistency gap already accepted in this codebase; not a
   blocker, but worth a defense-in-depth re-check if session-creation-under-race ever
   becomes an actual incident.
   Affects: T026 (Redis Caching Layer — same query-path consistency territory).

2. **`startScan` still has no existing-active-session guard (duplicate of the T023
   follow-up #1 self-match issue, re-confirmed present).** Two concurrent
   `POST /scan/start` calls from the same user create two `ScanSession` rows, both added
   to `scan:active_sessions` — pre-existing, out of T025's scope, not introduced or
   worsened by the new eligibility guard.
   Affects: T022, T023 (see existing entry under "From T023" for full detail — tracked
   there, referenced here for completeness since it surfaced again during this re-review).

3. **No unit tests exist yet for `UserVerifiedEventListener`,
   `TrustScoreUpdatedEventListener`, or the new `startScan` eligibility guard.** The only
   test file in discovery-service is the placeholder `DiscoveryServiceApplicationTests.java`.
   Recommend at minimum a duplicate-delivery (idempotency) test per listener and a
   suspended-caller-gets-403 test for the guard before this ships further.
   Affects: T026, T027 (natural points to introduce the service's first real test
   coverage alongside other discovery-service work).

## From T026 (Redis Caching Layer)

1. **`scan:{id}:gender` / `scan:{id}:women_only` still read/written via raw `RedisTemplate`
   in `ScanSessionServiceImpl.java` and `ScanQueryServiceImpl.java`, outside
   `DiscoveryCacheService`'s consolidation.** These two keys aren't in the ticket's own key
   table, and this leaves the "one class owns every Redis key" guarantee slightly
   incomplete — a future key-format or TTL change to either key has two places to check
   instead of one. Fix: fold into `DiscoveryCacheService` as
   `cacheSessionGender()`/`getSessionGender()`/`cacheWomenOnlyFlag()`/`isWomenOnly()`/
   `removeSessionIdentity()`, same "no TTL, explicit delete" shape as `scan:active_sessions`.
   Affects: T022, T023 (own the current call sites).

2. **TTL property names deviate from the ticket's suggested naming.** Ticket spec suggested
   `cache.ttls.session-location` etc.; implementation uses
   `discovery.cache.session-location-ttl-seconds` etc. under the existing `discovery.*`
   Options-pattern root. Functionally equivalent, consistently documented, no action needed
   unless a future ticket wants literal alignment with ticket text.
   Affects: none currently — cosmetic only.

3. **`log.warn(..., ex.toString())` in `DiscoveryCacheService.getProfile`/`getBlocklist`
   could surface an upstream response-body fragment in a log line** (server-side log only,
   not an HTTP response — no active leak today). Consider
   `ex.getClass().getSimpleName() + ": " + ex.getMessage()` for tighter control, matching
   the caution already applied to Kafka payload logging elsewhere in this service.
   Affects: whichever future ticket first hardens logging across discovery-service (no
   specific task ID yet in PROGRESS.md).

4. **No unit tests for `DiscoveryCacheService`'s hit/miss/failure branches.** Not a
   regression (no test precedent exists elsewhere in this service either), but this class
   has the most meaningful conditional logic added by this ticket and would benefit from
   Mockito coverage of the `getProfile`/`getBlocklist` cache-hit, cache-miss-success, and
   cache-miss-failure paths.
   Affects: T027 (next discovery-service ticket — natural point to introduce the service's
   first real test coverage, per the existing T025 follow-up #3 on the same gap).

5. **`TrustScoreUpdatedEventListener` has no path to re-add a user to `scan:eligible_users`
   after a suspension is later lifted** (`requestLimit` recovering above 0). Pre-existing
   from T025, unchanged by this refactor — re-confirmed present while reviewing this
   listener's cache-service integration.
   Affects: T025's original scope area — flag for whichever future ticket revisits trust
   score recalculation / eligibility gating in discovery-service.

## From T028 (Discovery Service Docker Image)

1. **Manifest's stated root cause for the `docker images` (~450MB) vs. actual image size
   (~152MB) gap is wrong.** `.claude/manifests/T028.json` attributes the discrepancy to a
   "Docker Desktop containerd image-store virtual-size reporting quirk with multi-stage
   BuildKit builds" and specifically implies BuildKit attestation/SBOM metadata as the
   mechanism. Reviewer rebuilt with `--provenance=false --sbom=false` and the `docker
   images` number was unchanged (still ~450MB), while `docker inspect --format {{.Size}}`
   and `docker save` both independently corroborated ~152MB — so the top-line conclusion
   ("trust `docker inspect`, not `docker images`, for this image") is correct and
   reproducible, but the specific *mechanism* named (BuildKit attestation) is not what's
   actually happening; the real cause is an unconfirmed containerd-snapshotter display
   quirk (`docker info` shows `driver-type: io.containerd.snapshotter.v1` active on this
   machine). Whoever writes the size-budget check for the remaining Java services should
   use `docker inspect .Size` (or `docker save` size) as the authoritative metric, not
   `docker images`, and should also be aware `docker history`'s summed layer size comes to
   ~298MB uncompressed — only ~2MB under the 300MB budget — so an uncompressed-size-based
   check could flip pass/fail depending on which metric is used.
   Affects: T034, T046, T055, T061 (remaining Java service Docker Image tickets — all will
   hit the same `docker images` display quirk and need the same inspect-based verification
   approach).

2. **`SPRING_PROFILES_ACTIVE=docker` is set for connection-service, rating-service,
   report-service, and admin-service in `docker-compose.override.yml`, but none of those
   services' `application.yml` files have been confirmed to define a `docker` profile
   document** — the exact same latent bug T028 discovered and fixed for discovery-service
   (activating a nonexistent Spring profile silently no-ops, so `SPRING_DATASOURCE_USERNAME`/
   `PASSWORD` never bind from the `dev`/`prod`-only placeholders, and the app connects to
   SQL Server with an empty username — error 18456). discovery-service was fixed by adding
   explicit `SPRING_DATASOURCE_USERNAME=sa` / `SPRING_DATASOURCE_PASSWORD=${SA_PASSWORD:?...}`
   env vars directly in the override file, bypassing the profile system entirely via Spring's
   relaxed env-var binding. The other four services will hit this the moment they get a
   Dockerfile and are run for the first time, unless fixed proactively.
   Affects: T034 (Connection Service Docker Image — next in line, will hit this first),
   T046 (Rating Service Docker Image), T055 (Report Service Docker Image), T061 (Admin
   Service Docker Image).

## From T027 (Resilience4j Circuit Breaker (User Service calls))

1. **`getBlocklist` fails open (returns an empty list) during a User Service outage,
   meaning a previously-blocked user can reappear in someone's discovery radar for the
   duration of the outage.** `DiscoveryCacheService.getBlocklist`'s catch block returns
   `List.of()` uncached rather than degrading to a cached blocklist the way `getProfile`
   degrades to a cached-or-placeholder profile. Given CLAUDE.md treats blocking as safety
   infrastructure alongside women-only mode and trust-score suspension, this is a
   product-safety trade-off, not just a technical fallback shape, and should be a
   conscious sign-off rather than an emergent side effect of "keep the endpoint from
   500ing."
   Affects: whichever future ticket first implements User Service's `/blocks` controller
   (no task ID yet in PROGRESS.md/SPECS.md — the endpoint doesn't exist yet, per
   `UserServiceClientImpl`'s own "Known limitation" comment), and T028 (Discovery Service
   Docker Image — flag for tech-lead sign-off before this phase closes).

2. **Stale/contradictory comment in `DiscoveryCacheService.getBlocklist`.** The doc
   comment directly above the method claims "Caching the empty result for the full TTL is
   deliberate too," but the actual `catch` block does not call `cacheBlocklist` — it
   returns `List.of()` uncached, matching the WARN log line's own "(fail-open, not
   cached)" wording two lines below. The comment teaches the wrong lesson to the next
   reader in a project whose explicit convention is comments-explain-the-pattern.
   Affects: T028 (cheap enough to fix directly whenever this file is next touched).

3. **`permitted-number-of-calls-in-half-open-state` left at Resilience4j's default (10)
   rather than tuned to "one cautious probe."** The ticket's own plain-English
   explanation describes half-open as letting through one trial call to check recovery,
   but `application.yml`'s `resilience4j.circuitbreaker.instances.userService` block
   doesn't set this property, so up to 10 calls are actually permitted through before the
   breaker decides to re-close or re-open. Functionally still satisfies the literal
   acceptance criterion ("the next call attempts a real request"), but the configured
   behavior doesn't match what this ticket teaches about half-open.
   Affects: T027 itself (cheap one-line config fix next time this file is touched), or
   whichever future ticket does a circuit-breaker tuning/hardening pass.

4. **No `slow-call-duration-threshold` (or explicit WebClient response timeout)
   configured.** Failure-rate counting alone doesn't trip the breaker on a *hanging* (not
   erroring) User Service — only genuine exceptions count as failures today. This
   partially undercuts the ticket's own stated goal ("stop hammering a struggling
   dependency") for the slow-but-not-down case, distinct from the down case this ticket
   does handle correctly.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — flag for whichever
   ticket next does a resilience/timeout hardening pass across discovery-service's
   WebClient calls.

5. **`DiscoveryCacheService.degradedProfile`'s own Redis read is unguarded** — a
   simultaneous Redis outage during the fallback path (circuit open or User Service call
   failed, AND Redis unreachable at the same moment) throws uncaught up through
   `getProfile`/`findNearby` to whatever the controller does with an unhandled exception.
   Low real-world likelihood in practice (`findNearby` already depends on Redis earlier
   for session location/active-sessions/eligibility, so a genuine Redis outage would
   already fail the request before reaching this point), but the "always returns
   something useful" promise this ticket makes has an unguarded seam here.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — flag for a future
   Redis-resilience ticket if discovery-service ever gets its own circuit breaker/retry
   layer around Redis itself.

## From T029 (Spring Boot Setup + DB Schema)

1. **`ConnectionRequest.status` has no `@Builder.Default`, so a future create-flow that
   forgets `.status(...)` will INSERT an explicit `NULL` instead of falling back to the
   DB's `DEFAULT 'PENDING'`.** Hibernate lists every mapped, non-`insertable=false` column
   in its generated INSERT using whatever the Java field currently holds — `null` included
   — so the column's own `DEFAULT 'PENDING'` (V001) never gets a chance to fire once
   Hibernate is involved. Fix: `@Builder.Default private ConnectionStatus status =
   ConnectionStatus.PENDING;` on `ConnectionRequest.java`, added when the first
   `.save()` call is written.
   Affects: T030 (Request Lifecycle Endpoints — first ticket to actually construct and
   persist a `ConnectionRequest`).

2. **`ConnectionServiceApplicationTests.contextLoads()` has no `@ActiveProfiles` and
   `application.yml` only defines `spring.datasource.url` inside the `dev`/`prod` profile
   documents**, so a bare `mvn test` with no `SPRING_PROFILES_ACTIVE` set and no env vars
   exported will likely fail with "Failed to configure a DataSource: 'url' attribute is
   not specified." Inherited unchanged from `discovery-service`'s T021 template (same gap
   confirmed there) — not new to this ticket, but worth fixing once real tests exist.
   Affects: T081 (Unit Tests: Java Services — natural point to give every Java service's
   placeholder test a working `@ActiveProfiles`/test-profile setup).

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
