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

## From T032 (Kafka Producer: connection.accepted + connection.expired)

1. **Two comments still describe the pre-fix eventId-minting location, now factually wrong
   (comment-only, zero runtime impact).** During review, cycle 1 found the outbox row's `id`
   was minted independently (`UUID.randomUUID()` inside `OutboxServiceImpl.publish`) from the
   `event_id` already baked into the JSON payload by the caller — fixed in cycle 2 by changing
   `IOutboxService.publish` to accept an `eventId` parameter and having `OutboxServiceImpl` do
   `UUID.fromString(eventId)` instead of minting its own. Two comments were never updated to
   match: `OutboxEvent.java` (~lines 45-50) and
   `V004__outbox_partition_key_and_relay.sql` (~lines 22-31) both still say
   "`OutboxServiceImpl.publish` mints this id with `UUID.randomUUID()`" — it no longer does;
   `ConnectionServiceImpl` mints it now, at each of its three `outboxService.publish(...)` call
   sites, and just passes the string through. A reader trusting these comments would look in
   the wrong class to understand where the idempotency key actually originates — exactly the
   confusion the cycle-2 fix was meant to resolve.
   Fix: reword both comments to name `ConnectionServiceImpl` as the minting site and
   `OutboxServiceImpl.publish` as the conversion site only.
   Affects: T091, T093 (Phase 14, shared-java-lib outbox module — the next work to touch this
   exact mechanism and the most likely place someone copies this class as a reference).

## From T031 (Request TTL Expiry Job)

1. **AC2 ("`connection.expired` event published per expired request") is only verifiable
   at the outbox-table level right now — `OutboxRelay`, the component that actually reads
   the `outbox` table and calls Kafka, does not exist anywhere in the codebase yet.** T031's
   own ticket text asserts it "reuses the existing `OutboxRelay` (from T029/shared outbox
   wiring)," but that relay is T032's deliverable, and T032 is still `status: draft`. T031's
   code correctly writes the `connection.expired` outbox row inside the same `@Transactional`
   boundary as the status transition (matching the Outbox Pattern exactly), so this is a
   ticket-sequencing/documentation gap, not a defect in T031 itself — but until T032 ships,
   every expired connection's outbox row will sit at `status = 'PENDING'` forever, and
   Notification Service will never receive a single `connection.expired` event no matter how
   many requests expire.
   Affects: T032 (Kafka Producer: connection.accepted + connection.expired — must actually
   build `OutboxRelay` for this event to ever reach Kafka; also worth correcting T031.md's
   text once T032 lands, so it no longer claims a relay that didn't exist yet at implementation
   time).

## From T031 design discussion (outage-window event staleness) — surfaced in conversation, not `/review-task`

1. **If Kafka is down long enough for a connection's 10-minute TTL to elapse, the outbox
   relay will eventually replay `connection.requested` for a connection that has already
   moved to `EXPIRED` in Connection Service's own database by the time Notification Service
   processes it.** Walked through end-to-end: `createConnection` writes the
   `connection.requested` outbox row (`ConnectionServiceImpl.java:139`); if Kafka is still
   down 10 minutes later, the scheduler's `expireConnectionRequest` writes the
   `connection.expired` outbox row (`ConnectionServiceImpl.java:314-319`) for the same
   connection; both sit `PENDING` until Kafka recovers, then `OutboxRelay` (T032) publishes
   both, back to back. This is expected, safe behavior, not a bug — the outbox pattern
   promises an event is a true historical fact, never that it's still current — but it is
   only *safe* (rather than *confusing*) if two things hold: (a) the consumer never assumes
   the event describes the present tense, only treats it as "this happened" and lets its own
   action re-validate current state before mutating anything, and (b) same-connection events
   stay in order across a replay, which requires every producer to key Kafka messages by
   `connection_id`, not leave the key null/random.
   Resolution: T032's ticket already specifies partition key = `connection_id` for both
   topics (`.claude/tickets/T032.md` lines 22, 38, 69) and oldest-first outbox draining
   (line 63) — the ordering half was already correctly scoped before this discussion, no
   change needed there. Added a new "Event staleness after an outage" section to the
   `kafka-outbox` skill file explaining the concept generally, and added an explicit note +
   acceptance criterion to T049's spec in SPECS.md (Kafka Consumers, notification-service)
   stating the consumer must notify from the event payload alone — no synchronous
   re-validation call back to Connection Service — since the recipient's own next action
   (opening the request) already hits Connection Service directly and sees the real,
   current status.
   Affects: T049 (Kafka Consumers, notification-service) — ticket not yet created; when
   `/phase 8` generates `T049.md`, confirm this note carried over from SPECS.md.

## From T033 (Kafka Consumer: trust.score.updated — request limit cache)

1. **`InMemoryRequestLimitCache` is per-JVM, so in any deployment with more than one
   `connection-service` replica, only the pod owning a user's Kafka partition ever sees
   their updated `request_limit` — every other pod keeps serving `DEFAULT_REQUEST_LIMIT`
   for that user indefinitely.** This is ticket-mandated (T033 explicitly specified a local
   in-memory map), not an implementer deviation, and `IRequestLimitCache`'s interface
   already makes a future Redis-backed implementation a drop-in swap with zero caller
   changes (same shape as Discovery Service's own Redis-backed handling of the identical
   `trust.score.updated` event in `DiscoveryCacheService.cacheTrustMetadata`). Only
   breaks once `connection-service` scales beyond one instance — holds correctly in the
   current single-instance `docker-up` dev/QA environment.
   Affects: whichever future ticket first introduces multi-replica deployment or scaling
   for connection-service (no specific task ID yet in PROGRESS.md/SPECS.md) — recommend a
   dedicated "Redis-backed IRequestLimitCache" follow-up ticket at that point.

2. **`TrustScoreUpdatedConsumer` guards `request_limit` for `null` but not for a negative
   value.** A malformed/buggy producer publishing `request_limit: -1` is accepted and
   cached as-is; functionally fails closed (user is always throttled) rather than
   crashing, but it's an unvalidated boundary. Fix: add a guard clause alongside the
   existing null check.
   Affects: T033 itself (cheap fix next time this file is touched).

3. **The missing-required-field guard logs the entire parsed event object (`log.error(...,
   event)`), inconsistent with the "never log raw payload" discipline the JSON-parse-failure
   branch two lines above explicitly follows.** No sensitive field exists on this event
   today, so no active leak, but `TrustScoreUpdatedEvent`'s own `@JsonIgnoreProperties` already
   anticipates the schema growing new fields later. Fix: log only `event.eventId()`
   (or similarly redacted) there, matching the branch above it.
   Affects: T033 itself (cheap fix next time this file is touched).

## From T034 (Connection Service Docker Image)

1. **Ticket's own acceptance-criteria checkbox is unchecked despite `status: done`.**
   `.claude/tickets/T034.md:37` still shows `- [ ] docker compose up connection-service
   starts and health passes` even though the ticket frontmatter says `status: done` and
   `.claude/manifests/T034.json` records the criterion as PASS. Cosmetic self-consistency
   gap only — anyone auditing `.claude/tickets/` without cross-referencing the manifest
   would see an unchecked box on a "done" ticket.
   Affects: T034 itself (check the box next time this file is touched).

2. **No `docker` Spring profile document exists in any Java service's `application.yml`
   (confirmed again for connection-service, matching discovery-service's T028 precedent)
   — the fix correctly works around this via OS-env-var overrides in
   `docker-compose.override.yml`, but nothing in the codebase documents *why* a profile
   named `docker` is activated when it matches zero profile documents.** A future engineer
   could reasonably "clean up" the override env vars thinking a real `docker` profile
   handles datasource credentials, reintroducing the exact SQL error 18456 bug T028 and
   T034 both fixed.
   Affects: T046 (Rating Service Docker Image), T055 (Report Service Docker Image), T061
   (Admin Service Docker Image) — same latent gap will recur for each; consider a one-line
   comment in each service's `application.yml` near the `dev`/`prod` markers, or a shared
   note in the `docker-compose` skill file, explaining the env-var-override workaround so
   it isn't mistaken for dead config.

## From T030 (Request Lifecycle Endpoints) — surfaced in design discussion, not `/review-task`

1. **`createConnection` only checks the requester's active-connection status, not the
   recipient's — a request to an already-busy recipient is accepted and silently wastes
   one of the requester's limited `request_limit` slots.** `ConnectionServiceImpl
   .createConnection`'s Guard 2 (`ConnectionServiceImpl.java:105`) queries
   `findByStatusAndEitherParty(ACCEPTED, requesterId)` only; nothing checks whether
   `dto.recipientId()` already has an ACCEPTED connection elsewhere. That request persists
   as PENDING, but `acceptConnection`'s Guard 3 (`ConnectionServiceImpl.java:169-175`)
   checks BOTH parties, so it can never actually be accepted while the recipient stays
   busy — it just occupies one of the requester's limited outbound slots until it expires
   (and T031, the TTL expiry job that would even clear it, doesn't exist yet either). Fix
   direction discussed: reject at creation time with a `RECIPIENT_UNAVAILABLE`-style 409,
   symmetric with the existing requester-side check — or, better, keep already-connected
   users out of the requestable pool entirely via finding #2 below.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — natural candidates are a
   future Connection Service hardening ticket, or Discovery Service's candidate-filtering
   logic (T023/T026 already own the `status = "Looking for companion"` filter).

2. **Entering an ACCEPTED connection never flips a user's discovery status to "Not
   available," so Discovery Service keeps surfacing already-connected users as
   requestable.** REQUIREMENTS.md §3 documents status flipping automatically on scan
   start/exit, but nothing flips it on `connection.accepted`. If Discovery excluded
   already-connected users the way it excludes blocked/suspended ones, finding #1 above
   would largely disappear upstream instead of needing a Connection Service-side rejection.
   Affects: no specific future task yet — candidate for whichever ticket next revisits
   Discovery Service's candidate-filtering predicates (T023/T026's territory), paired with
   T032/T038 (the tickets that actually produce/consume `connection.accepted`).

3. **No way for a user to leave an ACCEPTED connection early if they're unhappy with the
   match — only mutual "Met Successfully" or the 2-hour chat timeout ends one.**
   `ConnectionStatus` (`ConnectionStatus.java`) only defines `PENDING/ACCEPTED/DECLINED
   /EXPIRED` — no `CANCELLED` or equivalent early-exit state, even though the enum's own
   comment notes adding one is meant to be a simple data change. Until this exists, a
   recipient who'd rather switch to a better-matched incoming request has no supported
   path to free themselves up.
   Affects: no specific future task yet — candidate for T037 (Chat Lifecycle) if
   chat-closing ever needs a corresponding connection-side status transition, or a
   dedicated future ticket if SPECS.md gains a "cancel connection" requirement.

## From T043 (Trust Score Algorithm)

1. **Missing null-guard on `RatingSnapshot.tags()` — `TrustScoreCalculator.ratingValue()`
   throws an uncaught `NullPointerException` for the whole calculation if any single
   rating's tag list is `null` rather than empty.** `TrustScoreCalculator` only has an
   existing test proving an *empty* tag list is treated as neutral
   (`emptyTagListOnARatingIsNeutralNotNegative`) — there is no equivalent coverage or guard
   for `tags() == null`. Today nothing in the codebase can actually construct a `null`-tags
   `RatingSnapshot` (the type doesn't exist outside this ticket's own tests yet), so this is
   latent, not yet reachable. It becomes reachable the moment T044's `RatingService` writes
   the mapper that deserializes `Rating.tags` (a raw `NVARCHAR(MAX)` JSON string) into
   `List<RatingTag>` and builds the real `RatingSnapshot` list — if that mapper ever returns
   `null` for a malformed/legacy JSON row (instead of `List.of()`), one bad historical rating
   would poison that user's entire trust-score recalculation with an unhandled exception
   instead of a usable score.
   Fix: either null-coalesce in `RatingSnapshot`'s compact constructor (`tags = tags == null
   ? List.of() : tags;`) or guard at the top of `ratingValue()` — the compact-constructor
   fix is stronger since it makes the invariant hold for every future caller, not just this
   one call site.
   Affects: T044 (Kafka Consumer + Producer — the ticket that writes the `Rating` →
   `RatingSnapshot` mapping and is the first real caller of `TrustScoreCalculator.calculate()`).

## From T037 (Chat Lifecycle (Open / Auto-close))

1. **TOCTOU race lets the 2-hour auto-close poller close a room that is mid-way through
   its 5-minute "met successfully" grace period, recording the wrong `closed_reason`.**
   `ChatAutoCloseHostedService.PollOnceAsync` (`HostedServices/ChatAutoCloseHostedService.cs:89-95`)
   selects stale rooms via `FindOpenRoomsOlderThanAsync` (filtered on `Status == Open`), but
   by the time it calls `CloseChatRoomService.CloseRoomAsync` for a given room, both
   participants may have since confirmed "met" and `ChatLifecycleService` may have already
   flipped that same room to `CLOSING` via `TransitionToClosingAsync`. Neither
   `CloseChatRoomService.CloseRoomAsync`'s in-code guard (`CloseChatRoomService.cs:51`,
   checks `Status == Closed` only) nor `ChatRoomRepository.CloseRoomAsync`'s Mongo filter
   (`ChatRoomRepository.cs:116`, `Status != Closed`) is strict enough to exclude `CLOSING` —
   so the poller's write succeeds, finalizing the room with `closed_reason = "timeout"`
   instead of letting the grace timer finish and record `"met_successfully"`. Not a
   duplicate-close or data-corruption bug (the atomic idempotency guard still prevents two
   closes), just an incorrect reason code and a skipped grace delay in a narrow timing
   window.
   Fix: give the auto-close path a stricter guard requiring `Status == Open` specifically
   (matching what `FindOpenRoomsOlderThanAsync` actually verified), leaving the grace-timer
   path's looser `!= Closed` filter as-is since it is expected to transition from `CLOSING`.
   Affects: T039 (Kafka Producer: chat.closed — the `closed_reason` field this bug can
   mis-populate is exactly what T039's relay will publish downstream to Rating Service).

2. **A room that reaches `CLOSING` and then crashes before its grace timer completes has no
   fallback recovery path.** `ChatLifecycleService.ScheduleGraceClose`'s `Task.Delay`-based
   timer is in-process memory only (a known, ticket-accepted limitation) — if chat-service
   restarts mid-grace-period, the timer is lost. Compounding this: `CloseChatRoomService
   .CloseRoomAsync` deletes messages (`CloseChatRoomService.cs:72`) *before* the atomic
   room-close write, so if the crash also happens to land in that exact window, the room is
   stuck in `CLOSING` with its messages already gone — and `ChatAutoCloseHostedService`'s
   poller only ever queries `Status == Open` (`ChatRoomRepository.cs:78-83`), so a
   `CLOSING` room is invisible to it and never gets picked back up. Low-probability (two
   separate failure windows have to both be hit), accepted as out of scope for this ticket,
   but worth a recovery mechanism eventually.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — candidate for whichever
   ticket next hardens chat-service's lifecycle (e.g. extending the auto-close poller to
   also sweep `CLOSING` rooms stuck past some grace-period-plus-margin threshold), or a
   dedicated ops/reliability pass.

## From T036 (SignalR Hub + Real-time Messaging)

1. **`ChatHub`'s error paths don't carry the project's `{ code, message, traceId }` error
   envelope.** `HubException` (thrown from `JoinRoom` and `SendMessage` on missing identity
   or a non-participant caller — `ChatHub.cs:42,47,69,79`) only carries a plain string
   message, no machine-readable `code`, no `traceId` to correlate with a server log line —
   unlike `ChatController`'s HTTP error paths (`NOT_A_PARTICIPANT`, `CHAT_ROOM_NOT_FOUND`,
   etc.), which are machine-parseable by the Angular client and support tooling. Pre-existing
   since `SendMessage`'s original error handling, not introduced by this ticket's fix cycle.
   Fix direction: encode a `code` prefix in the `HubException` message (e.g.
   `"NOT_A_PARTICIPANT: You are not a participant in this chat."`), or explicitly document
   SignalR's `HubException` as a deliberate, accepted exception to the envelope rule for
   this transport.
   Affects: T037 (Chat Lifecycle — next ticket to touch `ChatHub.cs`/error handling in
   chat-service).

2. **No max-length guard on message `content` in `ChatService.SendMessageAsync`.** Only an
   empty-check guard clause exists (`ChatService.cs:38-41`) — no upper bound. An
   authenticated participant could repeatedly send a multi-megabyte `content` string,
   inflating MongoDB storage and SignalR broadcast payload size before the TTL index reaps
   it. Low-severity storage/bandwidth concern, not a confidentiality breach.
   Fix: add a guard clause alongside the existing empty-check (e.g.
   `if (content.Length > <limit>) return new SendMessageOutcome(SendMessageResult.InvalidRequest, ...)`).
   Affects: T037 (Chat Lifecycle — next ticket to touch `ChatService.cs`'s message-sending
   path).

## From T040 (Chat Service Docker Image) — surfaced running a real `docker compose build`, not `/review-task`

1. **T040's own ticket text incorrectly cited T028/T034 (Java/Maven) as precedent for
   skipping a `.dockerignore`, when the actual .NET precedent it claims to copy —
   `api-gateway` (T014) — ships one specifically to prevent this exact failure.** Without
   it, `COPY . .` (`chat-service/Dockerfile:20`) copied the host's own locally-built
   `obj/` folder (present because the project had been opened/restored in Visual Studio)
   into the Linux build stage, overwriting the clean restore the container's own
   `RUN dotnet restore` had just produced. Visual Studio's restore bakes a Windows-only
   NuGet fallback path (`C:\Program Files (x86)\Microsoft Visual Studio\Shared\NuGetPackages`)
   into `obj/chat-service.csproj.nuget.g.props`; `dotnet publish --no-restore` then fails
   immediately inside the Linux container with `MSB4018: Unable to find fallback package
   folder`. The adversarial review that approved T040 couldn't catch this — no Docker
   engine was available in that environment, so AC1 was only verified by static analysis,
   not an actual build. Fixed by adding `src/services/chat-service/.dockerignore`
   (`bin/`, `obj/`, `.vs/`), matching `api-gateway/.dockerignore` and
   `user-service/.dockerignore` exactly.
   Affects: T050 (Notification Service Docker Image — the fourth and last .NET service
   Dockerfile; `notification-service/` already has local `bin/`/`obj/` on disk and no
   `.dockerignore` yet, so it will hit this identical failure on its first real
   `docker compose build` unless the ticket is written to add the `.dockerignore`
   upfront, copying `api-gateway`'s file rather than any Java service's deferral).

## From T038 (Kafka Consumer: connection.accepted)

1. **`ChatOpened` can be delivered twice to the same user in a narrow crash-then-redeliver
   window, even though room creation itself is now correctly deduplicated.** The
   `processed_events` idempotency record for a `connection.accepted` event is only written
   *after* `ChatRoomService.OpenRoomAsync` and the SignalR notification both succeed
   (`Messaging/Handlers/ConnectionAcceptedHandler.cs`). If the process crashes (or a
   transient SignalR/Redis-backplane send failure throws) between the notification and the
   `processed_events` write, Kafka redelivers the same event; the room-creation side
   converges safely on the single existing room via the fix-cycle-1 guard
   (`ChatRoomService.cs:35-43` existing-room check + the unique index/duplicate-key catch
   at `ChatRoomService.cs:74-95`), but `NotifyBothUsersAsync` runs again regardless, so both
   users' SignalR clients can receive a second `ChatOpened` event for the same chat room.
   This is a deliberate, documented trade-off (avoiding a second idempotency mechanism just
   for the notification step), not an oversight — but it means the Angular/Capacitor
   client's `ChatOpened` handler must treat the event as safely re-deliverable (e.g.
   "join/open this chat if not already joined," not "assume at-most-once delivery").
   Affects: whichever future ticket builds the Angular chat-opened handler / SignalR client
   integration (Phase 9+, Angular PWA chat UI — no specific task ID yet in PROGRESS.md/SPECS.md).

2. **Two legitimate but easy-to-conflate shapes of the same duplicate-key-as-concurrency-backstop
   pattern now exist side by side in chat-service, with no comment contrasting them.**
   `ProcessedEventRepository`'s duplicate-key catch is a pure no-op (the record's existence
   is the only thing that mattered), while `ChatRoomService`'s duplicate-key catch
   re-fetches and returns the winning room (callers need an actual entity back). Both are
   correct for what each caller needs, but a learner skimming both files side by side could
   read the difference as an inconsistency rather than a deliberate choice.
   Affects: no specific future task — optional one-line comment in `ChatRoomService.cs`
   near the duplicate-key catch, next time that file is touched, contrasting it with
   `ProcessedEventRepository`'s no-op shape.

## From T035 (Chat Service Setup + MongoDB)

1. `Program.cs` reads `MONGO_TTL_SECONDS` via `int.TryParse` with a safe fallback for
   non-numeric input, but a syntactically valid negative value (e.g. `-7200`) is not
   rejected — it flows into `TimeSpan.FromSeconds(ttlSeconds)` and only fails deep inside
   the MongoDB driver with an opaque `MongoCommandException`, not a clear guard-clause
   message naming the misconfigured env var.
   Affects: any future chat-service ticket that touches `Program.cs` startup config
   (e.g. a later Phase 6 ticket wiring more env-driven options) should add
   `if (ttlSeconds < 0) throw new InvalidOperationException(...)` alongside the existing
   `TryParse` guard.

2. `Infrastructure/MongoIndexInitializer.cs`'s drop+recreate TTL-index logic has a narrow
   TOCTOU (check-then-act) race if two chat-service replicas cold-start at the same time
   after `MONGO_TTL_SECONDS` changed across a redeploy: both instances can read the same
   stale index name before either drops it, causing one replica's recreate to be
   immediately dropped by the other. Self-heals within the same startup window (no data
   loss), but the TTL index can be transiently absent. Not a risk today since chat-service
   runs as a single replica in Docker Compose.
   Affects: any future task that scales chat-service to multiple replicas (Docker Compose
   `deploy.replicas` or a later Kubernetes/orchestration ticket) should harden this —
   either pin an explicit `CreateIndexOptions.Name` and treat drop+recreate as best-effort,
   or catch and swallow `IndexOptionsConflict` on a direct `CreateOneAsync` as proof another
   replica already reconciled it.

3. T035's own ticket text says "configure via `process.env.MONGO_CONNECTION_STRING`," but
   the shipped code correctly follows the established `ConnectionStrings__ChatDb` /
   `GetConnectionString("ChatDb")` convention from T007 and user-service instead (judged
   correct in review — no code change needed). The same generic
   `process.env.MONGO_CONNECTION_STRING` wording also appears verbatim in SPECS.md for
   T051 (Report Service, Spring Boot + Spring Data MongoDB).
   Affects: T051 — when its ticket is created, it should specify the actual Spring Boot
   config convention (e.g. `spring.data.mongodb.uri` bound from an env var matching
   whatever naming convention report-service's docker-compose block ends up using) rather
   than reusing the same generic template phrase, so the ticket doesn't describe a
   connection-string approach that gets silently overridden during implementation again.

## From T046 (Rating Service Docker Image)

1. **Ticket's own "What to build"/"Agent implementation notes" sections describe the
   pre-existing `docker-compose.yml` rating-service build block as
   `context: ../../src/services/rating-service` + `dockerfile: Dockerfile` — a narrow,
   non-shared-lib-aware context.** The implementer found this claim was already stale
   relative to what the service actually needs (rating-service's `pom.xml` depends on
   `shared-java-lib`, a sibling module the Dockerfile must `COPY`/`mvn install` before
   rating-service's own build — impossible from a context scoped to `rating-service/`
   alone) and corrected `docker-compose.yml` to `context: ../../src/services` +
   `dockerfile: rating-service/Dockerfile`, matching the discovery-service (T028) and
   connection-service (T034) precedent. The fix is correct and verified working; only the
   ticket file's prose is now out of sync with the shipped code. Per this project's
   no-retroactive-docs convention, T046.md itself is not being edited after the fact.
   Affects: T055 (Report Service Docker Image), T061 (Admin Service Docker Image) — both
   tickets likely carry the same templated "context already scoped to the service's own
   folder" claim; whoever implements them should verify the actual `docker-compose.yml`
   build block (and whether that service's `pom.xml` depends on `shared-java-lib`) rather
   than trusting the ticket text, to avoid a `docker build` dependency-resolution failure
   on first run.

2. **Final image size (~133MB via `docker inspect`/`docker save`) is the implementer's
   self-reported figure from `.claude/manifests/T046.json`, not independently re-executed
   by the `/review-task` reviewer** (Docker commands are reserved for manual/human
   execution in this project, not run by review agents). Low risk — the same metric choice
   (inspect/save, not `docker images`) already proved reliable for T028/T034 — but AC2
   should get one human spot-check via the QA checklist (`.claude/qa/T046.qa.md` steps 6-7)
   before being treated as fully closed.
   Affects: none currently — closed out by the QA checklist itself; no downstream task
   needed unless the spot-check turns up a discrepancy.

## From T047 (FCM Setup)

1. **`FcmNotificationService` sends via `FirebaseMessaging.DefaultInstance` (an SDK-internal
   static global) instead of the `FirebaseApp` singleton already registered in DI via
   `AddSingleton` in `Program.cs`.** The registration is currently decorative — nothing
   resolves it. This blocks unit-testing `SendAsync` later, since there is no constructor
   seam to swap in a fake Firebase instance; CLAUDE.md explicitly prefers DI-container
   singletons over hand-rolled/implicit statics for exactly this reason.
   Fix: inject `FirebaseApp` into `FcmNotificationService`'s constructor and call
   `FirebaseMessaging.GetMessaging(firebaseApp).SendAsync(message)` instead.
   Affects: T082 (Unit Tests: .NET Services — the first ticket that will actually need to
   mock Firebase to test this class).

2. **`UserServiceClient.GetFcmDeviceTokenAsync` has no try/catch around the HTTP call** —
   only an error-status-code response is handled; a network failure or timeout against User
   Service (`HttpRequestException`/`TaskCanceledException`) propagates uncaught. No caller
   exists yet so nothing breaks today, but once a Kafka consumer drives `SendAsync`, an
   unhandled exception here during a User Service outage could crash or poison that
   consumer's processing loop.
   Fix: wrap the HTTP call in try/catch, return `null` on failure (same "can't push right
   now" treatment already given to a non-success status code), and add a short explicit
   `HttpClient` timeout (e.g. 5s) rather than relying on the 100s default.
   Affects: T049 (Kafka Consumers, notification-service — the first real caller of
   `IFcmNotificationService.SendAsync`).

3. **AC2 ("`SendAsync` with a real FCM token delivers a notification") cannot be exercised
   end-to-end through the real `UserServiceClient` path today, because User Service's
   `GET /users/{id}` does not serialize an `fcm_token` field yet** — confirmed by reading
   `PublicUserProfileDto` directly. `UserServiceClient` correctly calls the real, verified
   endpoint and reads the right property name (documented in its own code comment), so this
   will start working automatically once the field is added — this is a sequencing gap, not
   a bug, and verified via a stubbed client in `.claude/qa/T047.qa.md` step 4 in the
   meantime.
   **Decision (2026-10-20): the `fcm_token` column + a device-registration endpoint on User
   Service will be added alongside T049's own implementation**, not as a separate ticket —
   T049 is the first ticket that actually needs a real token to flow end-to-end (it's the
   first real caller of `IFcmNotificationService.SendAsync`), so the two land together.
   Affects: T049 (Kafka Consumers — Connection Lifecycle Fan-Out to SignalR + FCM) — see
   open question logged there about whether this goes into T049.md's own scope or a
   same-session companion change to User Service.

4. **`docker-compose.override.yml`'s notification-service block sets
   `KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-kafka:9092}`, the host-facing PLAINTEXT listener,
   instead of `kafka:29092`** (the internal container-to-container listener correctly used
   by rating-service's own block a few lines above). Inert today since T047 adds no Kafka
   code, but if copied forward unnoticed it will silently fail to connect once a consumer
   is added.
   Affects: T049 (Kafka Consumers, notification-service — the ticket that will actually
   read this env var).

## From T049 (Kafka Consumers — Connection Lifecycle Fan-Out to SignalR + FCM) — surfaced in conversation, not `/review-task`

1. **The API gateway's `JwtValidationMiddleware` only ever read the JWT from the
   `Authorization` header, which cannot work for a browser-initiated SignalR hub
   connection.** A browser's WebSocket upgrade request cannot carry a custom
   `Authorization` header — that's a platform restriction, not something this codebase
   controls — which is why the official SignalR JS client's `accessTokenFactory` instead
   sends the token as a `?access_token=` query-string parameter on the negotiate/connect
   requests. Both `notification-service`'s `/hubs/notifications` and `chat-service`'s
   `/hubs/chat` proxy through this same gateway (`notification-route`/`chat-route` in
   `api-gateway/appsettings.json`), so every real hub connection attempt would have 401'd
   at the gateway before ever reaching either hub's `OnConnectedAsync`. Fixed directly
   (`JwtValidationMiddleware.cs`'s `ExtractBearerToken`/new `IsHubRoute` helper): falls
   back to `?access_token=` only for paths containing `/hubs/`, so every other route still
   requires a real `Authorization` header (query strings end up in access logs/browser
   history far more than headers do, so this fallback is deliberately scoped narrow, not
   applied gateway-wide).
   **When T067 (Connection Request Flow) builds `NotificationService` (Angular) and its
   SignalR hub subscription, and T068 (Chat UI) builds the equivalent for `ChatHub`: use
   `accessTokenFactory` in `HubConnectionBuilder`, pointed at the real JWT from T063's
   `AuthService`/token storage — the gateway now expects exactly that query-string
   convention and will reject a hub connection that omits it.**
   Affects: T063 (Auth Flow — whatever token-access method `AuthService` exposes should be
   easy to pass into `accessTokenFactory`), T067 (Connection Request Flow — first real
   caller of this hub-auth convention for notification-service), T068 (Chat UI — same
   convention needed for chat-service's hub).

2. **`ProcessedEventRepository.RecordAsync` (`notification-service/Repositories/ProcessedEventRepository.cs:44-50`)
   uses a plain `StringSetAsync`, not `StringSetAsync(..., When.NotExists)`.** Compared
   against chat-service's Mongo equivalent (`ProcessedEventRepository.cs:42-59`), which
   uses `EventId` as the document `_id` and relies on Mongo's own unique-index
   `DuplicateKeyException` as a genuine database-enforced backstop if two consumer
   instances ever race past the `ExistsAsync` check at the same moment — notification-service's
   Redis version has no equivalent: if that same race happened here, both racers would
   silently overwrite the same key with no error, no detection, and (per the T049 review's
   Probe 4) a second duplicate push. Fix: use Redis's `SETNX` semantics
   (`When.NotExists`) so a losing racer's write visibly fails instead of silently
   succeeding, matching the strength chat-service's unique index already gives it.
   Affects: T049 itself (cheap one-line fix next time this file is touched).

3. **`processed_events` (Redis DB 2) now lives on the same Redis instance whose
   `redis.conf` (`src/infra/redis.conf:26-40`) explicitly disables persistence (`save ""`,
   no AOF) — a decision made for T008 on the premise that everything this instance holds
   is "derived data," safely rebuildable or meaningless after a restart (Discovery's scan
   cache, SignalR's backplane state).** T049's idempotency ledger doesn't fit that
   original justification: it isn't derived, and losing it on a restart/OOM-evict means
   Kafka messages still inside their 7-day retention window look "never processed" again
   and get re-handled — bounded today to "a duplicate push notification," which is why
   this was an acceptable trade-off for this specific use case, but it's a justification
   `redis.conf`'s own comment doesn't currently account for (it still only names the
   original two use cases).
   Affects: whichever future ticket next revisits Redis persistence/HA configuration for
   this instance (no specific task ID yet in PROGRESS.md/SPECS.md) — consider either
   updating `redis.conf`'s comment to acknowledge this third, non-derived tenant, or
   (if this ledger's durability ever matters more, e.g. a future consumer whose duplicate
   processing isn't harmless) moving `processed_events` to a persistence-enabled Redis
   instance/namespace rather than the shared cache-and-backplane one.

## From T050 (Notification Service Docker Image)

1. **`.env.example` has no `FCM_CREDENTIALS_JSON_HOST_PATH` entry, so a fresh clone will
   reproduce the exact empty-directory bind-mount crash this ticket just fixed.** This
   ticket was the first time `notification-service` ever actually ran in Docker, and
   discovered that `docker-compose.override.yml`'s
   `${FCM_CREDENTIALS_JSON_HOST_PATH:-./secrets/fcm-service-account.json}:/run/secrets/...:ro`
   mount auto-creates an empty directory (not a file) if the host path doesn't already
   exist — causing `GoogleCredential.FromFile` to throw `UnauthorizedAccessException` at
   startup. The fix this session was host-local (manually creating a syntactically-valid
   dummy service-account JSON at `src/infra/secrets/fcm-service-account.json`, now
   gitignored) — it does not travel with the repo. The only documentation of this
   one-time setup step today is one line buried in `.claude/qa/T047.qa.md:5`, not
   surfaced anywhere a developer starting from T050 alone would see it.
   Fix: add a commented `FCM_CREDENTIALS_JSON_HOST_PATH=` line (or an explicit setup
   note) to `.env.example`, and/or call this out in `src/infra/README.md`'s "getting
   started" steps.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — flag for whichever
   ticket next revisits onboarding docs / `.env.example` completeness, or fix directly
   next time either file is touched.

2. **`/health` (added this ticket) reports healthy regardless of whether the FCM
   credentials are real or the dummy placeholder — "healthy" means "the process is up,"
   not "push notifications actually work."** This is the correct scope for a liveness
   probe (matches the same deliberate liveness-only convention already documented on
   `chat-service`'s own `/health`), but it does mean a real deployment that forgets to
   swap the dummy credential for a production one would boot green and only fail
   silently the first time an actual push hits Firebase, with no startup-time signal
   distinguishing the two cases.
   Affects: no specific future task yet — informational only, worth a comment at the
   `/health` endpoint or a future readiness-vs-liveness hardening pass if this project
   ever adds a separate `/ready` check per service.

## From T048 (SignalR Notification Hub)

1. **`notification-service` has no `/health` endpoint**, unlike chat-service which has one
   wired up. Pre-existing gap from T047's scaffold, not introduced by T048, but worth
   closing before this service is treated as production-ready — a Docker Compose
   healthcheck or load balancer has nothing real to probe today.
   Affects: T050 (Notification Service Docker Image — the natural point to add a healthcheck,
   same as T028/T034/T040/T046 each did for their own service).

2. **The Redis backplane (`AddStackExchangeRedis`) has no `AbortOnConnectFail`/retry tuning
   for a Redis-down-at-startup scenario.** Mirrors chat-service's own already-approved
   pattern exactly — not a T048-specific regression, just inherited. If Redis is
   unreachable when notification-service boots, behavior is whatever the StackExchange.Redis
   client's untuned defaults do, not a deliberate choice.
   Affects: no specific future task yet in PROGRESS.md/SPECS.md — flag for whichever ticket
   first does a resilience/startup-hardening pass across the .NET services' Redis
   connections (would apply to chat-service too, not just notification-service).
