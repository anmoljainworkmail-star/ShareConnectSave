# Review Follow-ups

Non-blocking findings from `/review-task` that were APPROVED_WITH_MINOR_ISSUES rather than
sent back for rework. Each entry lists which future task(s) need to act on it.

`ticket-creator.md` reads this file when drafting a ticket for any service listed in
"Affects" — fold the note into that ticket's spec (or acceptance criteria) so it doesn't
require the person approving the ticket to remember a review from a prior phase.

Mark an entry `[resolved: T0XX]` once the affecting task addresses it — don't delete it,
so the audit trail of what was known and when survives.

---

## From T005 (OpenAPI Specs)

1. **Throttle/lockout status codes use 403/409 instead of 429.**
   `connection-service.yaml` `POST /connections` (403 for throttled/suspended) and
   `user-service.yaml` `POST /auth/otp/send` (409 for OTP lockout) don't follow the
   `429 Too Many Requests` convention, and no `ErrorResponse.code` values are defined to
   let a client tell "blocked" apart from "rate-limited."
   Affects: T017 (Phone OTP Verification), T029/T030 (Connection Service request lifecycle),
   T063/T067 (Angular auth + connection request UI — needs distinct messaging per code).
   Action: when implementing, either switch to 429 or — if keeping 403/409 for
   REQUIREMENTS.md reasons — define explicit `code` enum values (`THROTTLED`, `BLOCKED`,
   `OTP_LOCKED`) and document them for the frontend.

2. **`AuthResponse` returns `refresh_token` but no `/auth/refresh` endpoint exists.**
   Likely an intentional Phase 3 scope deferral, not a dropped requirement.
   Affects: T015/T016 (User Service, Google OAuth + JWT Issuance).
   Action: add `POST /auth/refresh` to `user-service.yaml` and implement it in T016,
   or explicitly confirm token refresh is out of scope for this project and remove the
   field instead.

3. **Request schemas don't set `additionalProperties: false`.**
   `GoogleAuthRequest`, `ProfileUpdateRequest`, etc. silently accept unexpected extra
   fields instead of rejecting them at the schema-validation layer.
   Affects: any task implementing request DTOs against these specs (T015+, T021+, T029+).
   Action: low priority — add `additionalProperties: false` to request schemas when
   touching them, no dedicated ticket needed.

## From T006 (SQL Server Database Schemas)

1. **Discovery Service has no Flyway/mssql-jdbc runtime dependency wired up yet**, so the
   `V001__discovery_service_initial_schema.sql` migration cannot actually execute — `pom.xml`
   has no `flyway-core`/`flyway-sqlserver`/`mssql-jdbc`, and `application.properties` has no
   `spring.datasource.*`/`spring.flyway.*` config. This is expected at this stage (T006 is
   schema-only) but must not be forgotten when the project is bootstrapped.
   Affects: T021 (Discovery Service — Spring Boot Project Setup).
   Action: when wiring up the datasource, make sure Flyway runs through the JDBC driver
   (not a raw `sqlcmd` test) so the migration's `SET ARITHABORT ON` etc. actually take
   effect — JDBC defaults `ARITHABORT` to OFF unlike `sqlcmd`, which is exactly the gap
   this migration's SET block exists to close.

2. **Redundant index on `ratings.connection_id`.** `idx_ratings_connection_id` (single-column)
   is now redundant next to the unique composite `idx_ratings_connection_id_rater_id
   (connection_id, rater_id)` — SQL Server can already satisfy a "find by connection_id"
   query using the composite index's leading column. Not incorrect, just unnecessary
   storage/write overhead.
   Affects: T041 (Rating Service — Spring Boot Setup + DB Schema).
   Action: low priority — drop `idx_ratings_connection_id` in a follow-up migration when
   next touching this schema, no dedicated ticket needed.

## From T007 (MongoDB Setup)

1. **Falsy-zero bug in TTL parsing.** `01-init-chat.js:43` uses
   `parseInt(process.env.MONGO_TTL_SECONDS, 10) || 7200`. Since `0` is falsy in JavaScript,
   an explicit `MONGO_TTL_SECONDS=0` (e.g. someone deliberately testing "disable retention")
   is silently discarded and replaced with the 7200s default. There's also no guard against
   a negative value — `MONGO_TTL_SECONDS=-5` passes straight through to
   `createIndex({sent_at:1}, {expireAfterSeconds: -5})`, which MongoDB rejects, crashing the
   init script non-obviously (the error only surfaces in `docker-entrypoint-initdb.d` logs).
   Affects: T035 (Chat Service Setup + MongoDB — TTL index on messages.sent_at).
   Action: replace with an explicit `Number.isNaN()` check instead of `||`, and reject
   negative values before calling `createIndex`, when T035 next touches this TTL config.

2. **Theoretical TOCTOU in `ensureCollection`.** `01-init-chat.js:50-55` and
   `02-init-report.js:30-35` do a check-then-act (`getCollectionNames().indexOf(name) === -1`
   → `createCollection(name)`) with no atomicity between the check and the create. Not
   reachable under the current execution model (Docker runs each init file once, sequentially,
   single container) — only a real risk if the init model ever changes to allow concurrent or
   parallel script execution.
   Affects: T035 (Chat Service Setup + MongoDB), T051 (Report Service — Spring Boot +
   Spring Data MongoDB Setup).
   Action: low priority — add a one-line comment acknowledging the race if either task
   changes how/when these scripts run; no fix needed under the current single-run model.

3. **Ticket AC3 wording is inconsistent with its own implementation notes.** T007's
   "What to build" section asks for one compound `(chat_id, sender_id)` index, its
   "Agent implementation notes" section instead calls for two separate single-field indexes
   (and explains why — a compound index can't efficiently serve a `sender_id`-only
   moderation-lookup query), and the acceptance criterion itself says "user_id," a field
   that doesn't exist anywhere in the schema (the actual field is `sender_id`). The code
   correctly followed the more detailed implementation notes; only the ticket text is wrong.
   Affects: T035 (Chat Service Setup + MongoDB), T051 (Report Service Setup) — both will
   reference T007 as prior art when writing their own tickets/specs.
   Action: no code change needed. When drafting tickets for T035/T051, don't copy T007's
   AC wording verbatim — use "sender_id" and describe the two-single-field-index rationale
   directly instead of "compound index."

## From T008 (Redis Setup)

1. **Misleading network-exposure comment on the Redis port mapping.**
   `docker-compose.yml:146` claims Redis "never leaves the dev Docker network/localhost,"
   but `ports: "6379:6379"` at `:158-159` publishes to `0.0.0.0` on the host — every
   network interface the dev machine has (Wi-Fi, Ethernet, VPN), not just loopback. On a
   shared network (this is explicitly a training/workshop project), anyone else on that
   network segment can reach Redis with no auth and run `KEYS *`/`FLUSHALL`. The same
   port-publishing pattern exists for SQL Server (1433), MongoDB (27017), and Kafka (9092)
   in the same file, so this isn't new to T008 — but T008's comment is the one that
   incorrectly asserts safety.
   Affects: none of the currently-scoped SPECS.md tasks own "harden local dev network
   exposure" as dedicated work — this is a stack-wide `docker-compose.yml` hygiene item
   from T004, not a single future service ticket.
   Action: either bind loopback-only (`"127.0.0.1:6379:6379"`, and consider the same for
   1433/27017/9092) or correct the comment to state the actual exposure and why it's
   accepted for a local training machine. No urgency pre-push; revisit if this stack is
   ever run somewhere less trusted than a personal dev machine.

2. **`SIGNALR_REDIS_CONNECTION` has no Redis database index.**
   `docker-compose.override.yml:43` (chat-service) and `:57` (notification-service) set
   `SIGNALR_REDIS_CONNECTION=${REDIS_CONNECTION:-redis:6379}` — a bare `host:port` with no
   `,defaultDatabase=1`. `README.md:30` designates DB 1 for the SignalR backplane, but
   StackExchange.Redis defaults to DB 0 when no index is given, which is the same DB
   Discovery Service's cache-aside data lives in. Not exploitable yet — no consuming code
   exists — but whoever writes the SignalR startup code will silently land on DB 0 unless
   they remember to append the index themselves; nothing about the variable's name or
   shape hints one is needed.
   Affects: T036 (SignalR Hub + Real-time Messaging — Redis backplane DB 1), T048 (SignalR
   Notification Hub — Redis backplane DB 1, shared with Chat).
   Action: when implementing T036/T048, append `,defaultDatabase=1` to the connection
   string (or set it via `ConfigurationOptions.DefaultDatabase` in code), and consider
   renaming/commenting `SIGNALR_REDIS_CONNECTION` in the override file now so the missing
   index isn't missed later.

## From T011 (YARP Gateway Project Setup)

1. **`docker-compose.yml` healthcheck targets a `/health` endpoint the gateway never maps.**
   `docker-compose.yml:237-238` configures `api-gateway`'s healthcheck against
   `http://localhost:8080/health`, but `Program.cs` only calls `MapReverseProxy()` — no
   `/health` endpoint exists. Once a Dockerfile exists and the container actually builds,
   `docker compose ps` will show `api-gateway` as permanently `unhealthy`. Confirmed this
   is pre-existing and not unique to the gateway — `user-service/Program.cs` has the exact
   same gap against its own compose healthcheck.
   Affects: T014 (Gateway Docker Image — will surface the failing healthcheck as soon as
   the container is built), T090 (Health Checks Consolidation — the ticket that actually
   owns adding `/health` per service).
   Action: when T014/T090 land, map a `/health` endpoint (e.g. `app.MapGet("/health", ...)`
   or `MapHealthChecks("/health")`) before relying on the compose healthcheck to pass.

2. **No `app.UseExceptionHandler(...)` registered in the gateway's `Program.cs`.**
   Harmless today — there's no custom code in the gateway that can throw, and YARP's own
   forwarding failures are already caught internally and turned into a bare `502` with no
   leaked destination info. But `ASPNETCORE_ENVIRONMENT=Development` (docker-compose.yml:235)
   auto-enables the ASP.NET Core Developer Exception Page for any *unhandled* exception, and
   the next two tickets are exactly the ones likely to introduce code that can throw (JWT
   parsing, rate-limit state).
   Affects: T012 (JWT Validation Middleware), T013 (Rate Limiting Middleware).
   Action: add `app.UseExceptionHandler(...)` (mapped to the shared error envelope shape from
   T003) before either ticket introduces custom logic that can throw, so a future bug renders
   `{ code, message, traceId }` instead of a raw stack trace to an external caller.

3. **`REQUIREMENTS.md`'s service port table documents host-mapped ports, not the
   container-internal port every service actually listens on.** The T011 ticket itself
   copied this table verbatim (`user-service: port 8081`, etc.), which would have been wrong
   to paste directly into YARP's cluster config — every service listens on `:8080` inside
   the Docker network (`docker-compose.yml`'s `"<host-port>:8080"` mappings), and 8081-8088
   only exist for reaching a service directly from the host machine. The actual T011
   implementation resolved this correctly (used `:8080` throughout), so this is a
   documentation-clarity gap, not a code defect.
   Affects: none of the currently-scoped SPECS.md tasks own updating `REQUIREMENTS.md`'s
   port table specifically — this is a stack-wide doc hygiene item, not a single future
   ticket's job.
   Action: low priority — correct or annotate the port table (e.g. add a "container-internal
   port" column, always 8080) whenever `REQUIREMENTS.md` is next touched, so a future ticket
   author doesn't copy a host-mapped port into a service's own cluster/destination config.

4. **Every downstream service publishes its own host port, so the gateway can be bypassed
   entirely.** `docker-compose.yml` gives `user-service`, `discovery-service`, and all 6
   other downstream services their own `"<host-port>:8080"` mapping (8081-8088), alongside
   the gateway's own `8080:8080`. This means `http://localhost:8082` reaches
   `discovery-service` directly today, with zero involvement from the gateway. That's
   harmless right now (T012/T013 don't exist yet), but every downstream service's own code
   is written to *trust* the `X-User-Id`/`X-User-Role` headers without independently
   validating a JWT (see `java-spring-boot.md`'s "JWT / identity" section: "userId is
   already validated and injected by the gateway"). Once T012 adds real JWT validation at
   the gateway, a request sent straight to `localhost:8082` with a hand-crafted
   `X-User-Id` header would still be accepted by `discovery-service` — no JWT, no gateway,
   no check — because nothing downstream re-verifies that header's origin. The gateway
   being a "single entry point" is only true in practice if it's the *only* reachable
   entry point.
   Affects: T012 (JWT Validation Middleware — this is the point at which the bypass
   actually becomes exploitable, not before), T013 (Rate Limiting Middleware — same
   bypass applies: hitting a service directly skips rate limiting too).
   Action: once T012/T013 land, either (a) stop publishing 8081-8088 to the host at all in
   the base `docker-compose.yml` and only expose them via `docker-compose.override.yml` for
   local per-service debugging (keeping them off by default), or (b) explicitly accept the
   exposure for this training/personal-dev-machine project and document why, same tradeoff
   already accepted for Redis/SQL Server/Kafka/Mongo under the T008 follow-up above. Revisit
   if this stack is ever run somewhere less trusted than a personal dev machine.

## From T012 (JWT Validation Middleware)

1. **The catch-all exception handler in `JwtValidationMiddleware` could mishandle a future
   cancellation-token change in the JWKS-fetch library.** `JwtValidationMiddleware.cs`'s final
   `catch (Exception ex)` block (added to close a malformed-token 500 bug) currently can never
   be reached via `context.RequestAborted` firing during a JWKS fetch, because the pinned
   `Microsoft.IdentityModel.Protocols` 8.2.1's `ConfigurationManager<T>.GetConfigurationAsync`
   does not honor the passed cancellation token at all — verified empirically with an isolated
   repro against the exact pinned package (three scenarios: mid-fetch cancellation, pre-cancelled
   token, concurrent callers — zero exceptions thrown in any case). If a future upgrade of that
   package starts honoring cancellation (arguably the technically correct behavior), an ordinary
   client disconnect mid-request would throw `OperationCanceledException`, get caught by the
   current catch-all, and attempt to write a 401 response to a connection that's already gone —
   likely throwing again from inside the catch block itself, reintroducing an unhandled-exception
   path triggered by routine client disconnects rather than any malicious or malformed input.
   Affects: none of the currently-scoped SPECS.md tasks own revisiting this file specifically —
   T013 (Rate Limiting Middleware) adds new middleware but doesn't touch `JwtValidationMiddleware.cs`,
   and T014 (Gateway Docker Image) doesn't touch application code. This is a NuGet-upgrade-triggered
   risk, not tied to any planned future ticket.
   Action: low priority — add a defensive
   `catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested) { return; }`
   positioned before the generic catch-all, whenever `JwtValidationMiddleware.cs` is next touched
   for another reason, or whenever `Microsoft.IdentityModel.Protocols`/`Microsoft.IdentityModel.Tokens`
   is upgraded (re-verify the cancellation-honoring behavior at that time, since this finding is
   pinned to the specific 8.2.1 version tested).

## From T010 (Scoop + JDK 21 Install Script)

1. **`dev-setup.ps1` never checks `javac -version`, only `java -version`/`scoop list`.**
   AC2 literally names both `java -version` and `javac -version` showing 21, but the script's
   JDK 21 verification logic only ever inspects `java`. Low real-world risk — a Temurin JDK
   install always ships `javac` alongside `java` from the same archive, so they can't drift
   apart in practice — but it's a literal gap against the written acceptance criterion.
   Affects: none of the currently-scoped SPECS.md tasks own `dev-setup.ps1` beyond T010 itself
   — no dedicated future ticket revisits this file.
   Action: low priority — add a parallel `javac -version` check (same regex, same `scoop list`
   gating) only if `dev-setup.ps1` is touched again for another reason; not worth a dedicated
   ticket on its own.

2. **Asymmetric warning detail between the two "PATH-shadowed" messages.** The "already
   present but shadowed" warning includes the detected non-21 version string via
   `$versionSuffix`; the "just installed but still shadowed" warning does not. Cosmetic only.
   Affects: none — same as above, no dedicated future ticket owns this file.
   Action: no action needed unless the file is touched again; align the second message's
   wording with the first's for consistency at that time.

3. **Theoretical non-anchored regex match in the JDK-version check.** `'"?21("|\.)'` isn't
   anchored to a word boundary before "21", so a hypothetical future JDK major version whose
   string embeds "21" mid-token (e.g. a fictional `"121.0.5"`) would false-positive as JDK 21.
   Not exploitable with any real JDK release (majors are nowhere near 121), purely a
   robustness note for the far future.
   Affects: none — no dedicated future ticket owns this file.
   Action: no action needed now; revisit only if this regex is ever copied into code that
   parses version strings from a source where triple-digit majors are plausible.
