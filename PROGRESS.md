# Task Progress

> **How to use:** Mark a task `[x]` as soon as `/implement T00X` completes and all acceptance criteria pass.  
> Start each session with `/status` to get the current state. Always implement the lowest-numbered unchecked task whose dependencies are all checked.

---

## Phase 0 — Foundation & Contracts

- [x] T001 — Monorepo Scaffold _(Root)_
- [x] T002 — Kafka Topic + Contract Definitions _(contracts)_
- [x] T003 — Shared Error Envelope Contract _(contracts + shared-java-lib)_
- [x] T004 — Docker Compose Infrastructure Services _(infra)_
- [x] T005 — OpenAPI Specs — All Services _(contracts/openapi)_

---

## Phase 1 — Infrastructure

- [x] T006 — SQL Server Database Schemas _(Flyway migrations)_
- [x] T007 — MongoDB Setup _(chat_db + report_db init scripts)_
- [x] T008 — Redis Setup _(config — DB 0 discovery cache, DB 1 SignalR backplane)_
- [x] T009 — Kafka Topics Init _(7 topics, partitions, retention)_
- [x] T010 — Scoop + JDK 21 Install Script _(dev-setup.ps1)_

---

## Phase 2 — API Gateway (.NET)

- [x] T011 — YARP Gateway Project Setup
- [x] T012 — JWT Validation Middleware _(Google JWKS, header injection)_
- [x] T013 — Rate Limiting Middleware _(100/min global, 10/min connections)_
- [x] T014 — Gateway Docker Image

---

## Phase 3 — User Service (.NET)

- [x] T015 — User Service Project + EF Core Setup
- [x] T016 — Google OAuth + JWT Issuance
- [x] T017 — Phone OTP Verification _(Twilio, lockout after 5 attempts)_
- [x] T018 — Profile CRUD _(GET/PATCH /users/me, photo upload)_
- [x] T019 — Identity Verification Badge _(Azure Face API, NO_BASE_PHOTO guard)_
- [x] T020 — Kafka Producer: user.verified

---

## Phase 4 — Discovery Service (Java)

- [x] T021 — Spring Boot Project Setup
- [x] T022 — Geospatial Schema + Scan Session _(location in Redis only, never SQL)_
- [x] T023 — GPS Discovery Query _(5 filters, women-only, WebClient to User Service)_
- [x] T024 — BLE Token Generation _(HMAC-SHA256, 5-min expiry, resolve endpoint)_
- [x] T025 — Kafka Consumer: user.verified + trust.score.updated
- [x] T026 — Redis Caching Layer _(DiscoveryCacheService, 10s TTL)_
- [x] T027 — Resilience4j Circuit Breaker _(User Service calls, 50% threshold, 30s wait)_
- [x] T028 — Discovery Service Docker Image

---

## Phase 5 — Connection Service (Java)

- [x] T029 — Spring Boot Setup + DB Schema
- [x] T030 — Request Lifecycle Endpoints _(PENDING→ACCEPTED/DECLINED/EXPIRED state machine)_
- [x] T031 — Request TTL Expiry Job _(@Scheduled every 1 min)_
- [x] T032 — Kafka Producer: connection.accepted + connection.expired
- [x] T033 — Kafka Consumer: trust.score.updated _(Caffeine cache for request limits)_
- [x] T034 — Connection Service Docker Image

---

## Phase 6 — Chat Service (.NET)

- [x] T035 — Chat Service Setup + MongoDB _(TTL index on messages.sent_at)_
- [x] T036 — SignalR Hub + Real-time Messaging _(Redis backplane DB 1)_
- [x] T037 — Chat Lifecycle _(OPEN→CLOSING→CLOSED, 5-min grace, 2h auto-close)_
- [x] T038 — Kafka Consumer: connection.accepted _(open chat room)_
- [x] T039 — Kafka Producer: chat.closed
- [x] T040 — Chat Service Docker Image

---

## Phase 7 — Rating Service (Java)

- [x] T041 — Spring Boot Setup + DB Schema
- [x] T042 — Rating Submission Endpoint _(tag validation, duplicate check)_
- [x] T043 — Trust Score Algorithm _(TrustScoreCalculator — pure function, no DB/Kafka)_
- [x] T044 — Kafka Consumer + Producer _(chat.closed → store; rating.submitted → recalculate → trust.score.updated)_
- [x] T045 — Trust Score Badges + Throttle Enforcement _(badge ≥4.5+10 ratings, suspended <2.5)_
- [x] T046 — Rating Service Docker Image

---

## Phase 8 — Notification Service (.NET)

- [x] T047 — FCM Setup _(Firebase Admin SDK)_
- [x] T048 — SignalR Notification Hub _(Redis backplane DB 1, shared with Chat)_
- [x] T049 — Kafka Consumers _(connection.requested, connection.accepted, connection.expired)_
- [x] T050 — Notification Service Docker Image

---

## Phase 9 — Report Service (Java)

- [x] T051 — Spring Boot + Spring Data MongoDB Setup
- [x] T052 — Report Submission _(reason categories, store to MongoDB)_
- [x] T053 — Escalation Threshold Logic _(count reports per target, threshold trigger)_
- [x] T054 — Kafka Producer: report.filed _(with DLQ on failure)_
- [x] T055 — Report Service Docker Image

---

## Phase 10 — Admin Service (Java)

- [x] T056 — Spring Boot + Spring Security Setup _(OncePerRequestFilter reads X-User-Role)_
- [x] T057 — Review Queue Endpoints _(sorted by report count desc)_
- [x] T058 — Trust Score Override _(admin writes directly to ratings_db)_
- [x] T059 — Account Suspend _(status flag in users_db, re-publishes trust.score.updated)_
- [x] T060 — Dashboard Queries _(Caffeine-cached stats, 60s TTL)_
- [x] T061 — Admin Service Docker Image

---

## Phase 11 — Angular Frontend

- [x] T062 — Angular PWA Setup _(ngsw-config, service worker, Lighthouse ≥90)_
- [x] T063 — Auth Flow _(Google GSI + OTP, AuthGuard, AuthInterceptor)_
- [ ] T064 — Radar UI Component _(SVG rings + CSS @keyframes sweep animation)_
- [ ] T065 — BLE Mode _(Web Bluetooth API, offline detection, iOS fallback message)_
- [ ] T066 — Discovery Filters Panel _(bottom sheet, radius slider, women-only toggle)_
- [ ] T067 — Connection Request Flow _(SignalR events, inbound request component)_
- [ ] T068 — Chat UI _(SignalR client, Met Successfully button, offline banner)_
- [ ] T069 — Rating Flow _(tag grid, mandatory on Met path, Skip on timeout)_
- [ ] T070 — Profile + Settings _(gender locked after first scan, women-only toggle)_
- [ ] T071 — Admin Dashboard UI _(lazy-loaded AdminModule, AdminGuard, data tables)_
- [ ] T072 — Offline State Handling _(service worker cache, BLE fallback trigger)_

_Note: T073–T080 reserved — not currently assigned._

---

## Phase 12 — Testing

- [ ] T081 — Unit Tests: Java Services _(JUnit 5 + Mockito)_
- [ ] T082 — Unit Tests: .NET Services _(xUnit + Moq)_
- [ ] T083 — Integration Tests: Java _(Testcontainers — Discovery, Connection, Rating)_
- [ ] T084 — Integration Tests: .NET _(Testcontainers — User Service, Chat Service)_
- [ ] T085 — E2E Tests _(Playwright: happy path, expiry, report flow)_
- [ ] T086 — Load Test: Discovery Query _(k6, P95 ≤500ms at 50VU)_

---

## Phase 13 — Observability

- [ ] T087 — OpenTelemetry: Java Services _(logstash-logback-encoder, OTLP exporter)_
- [ ] T088 — OpenTelemetry: .NET Services _(Serilog JSON, OTLP exporter)_
- [ ] T089 — Jaeger in Docker Compose _(all-in-one image, port 16686)_
- [ ] T090 — Health Checks Consolidation _(/health endpoint per service, readiness + liveness)_

---

## Phase 14 — Saga + Outbox

- [ ] T091 — Outbox Module: shared-java-lib _(OutboxEvent entity, OutboxRelay @Scheduled 500ms)_
- [ ] T092 — Outbox Middleware: .NET _(BackgroundService, AddOutbox<TDbContext>() extension)_
- [ ] T093 — Apply Outbox to All Java Services _(replace direct KafkaTemplate calls)_
- [ ] T094 — Apply Outbox to All .NET Services _(replace direct IProducer calls)_
- [ ] T095 — Consumer Idempotency _(ProcessedEvent table, 7-day pruning, all Java consumers)_
- [ ] T096 — ConnectionLifecycleSaga State Tracking _(saga_state table, 5-min timeout, compensating revert, connection.chat-failed topic)_

---

## Phase 15 — Transport Layer: Connector Tuning (Bonus, not part of the original 96-task list)

Everything ngrok doesn't teach — the connector layer (Tomcat/Kestrel) sitting between the public
tunnel and this project's own application code. Scoped to discovery-service and api-gateway only,
not all nine services.

- [ ] T097 — Tomcat Connector Tuning _(discovery-service, explicit threads.max/accept-count, Actuator metrics)_
- [ ] T098 — Kestrel Limits Tuning _(api-gateway, MaxConcurrentConnections vs. MaxConcurrentUpgradedConnections for SignalR)_
- [ ] T099 — Load Test: Connector Saturation _(k6, extends T086 — deliberately break then fix the pool under load)_
- [ ] T100 — Graceful Shutdown & Connection Draining _(SIGTERM mid-request, before/after evidence, discovery-service + api-gateway)_
- [ ] T101 — Where ngrok Actually Sits _(request-path write-up, no code)_

---

## Phase 16 — Cross-Service Gaps for Admin Service (Bonus, not part of the original 96-task list)

Discovered via a manual audit of admin-service's three downstream WebClient calls (T057):
two User Service endpoints and one Report Service endpoint were already being called by shipped
T057 code but never actually existed, and a third User Service call hit the wrong (privacy-
minimized) response shape. T059 is explicitly blocked on T102.

- [x] T102 — Admin Internal Account-Status Endpoints _(user-service, new AccountStatus field +
      3 internal-only endpoints, closes T057's getSuspendedUsers/getUser/setStatus gaps)_
- [ ] T103 — Report History by Target Endpoint _(report-service, exposes existing
      findByReportedId() query via GET /reports?reportedId=, closes T057's getReportHistory gap)_
- [x] T104 — Admin Bulk-Expire Connections Endpoint _(connection-service, new admin-only
      PUT /admin/connections/users/{userId}/expire-active, closes T059's ConnectionServiceClient gap)_

---

## Phase 17 — Cross-Service Gaps for Admin Service — Dashboard Stats (Bonus, not part of the original 96-task list)

Discovered via T060's own downstream-endpoint audit (GET /admin/stats): none of the 4 services it
needs stats from expose an aggregate-stats endpoint today. Tech Lead decision (2026-10-25):
companion tickets first, one narrow admin-only stats endpoint per owning service. T060 is
re-sequenced to depend on all four.

- [x] T105 — Admin Stats Endpoint — Total Active Users _(user-service, internal-only
      GET /users/internal/stats, closes T060's "total active users" gap)_
- [x] T106 — Admin Stats Endpoint — Total Scans Today _(discovery-service, new admin-only
      GET /admin/scan/stats, closes T060's "total scans today" gap — deliberately uses Spring
      Security @PreAuthorize method security instead of a manual guard clause, a third real
      pattern alongside T056's filter-chain config and T104/T107's @RequestHeader guard)_
- [x] T107 — Admin Stats Endpoint — Total Connections Accepted Today _(connection-service, new
      admin-only GET /admin/connections/stats, closes T060's "total connections today" gap)_
- [x] T108 — Top Reported Users Endpoint _(report-service, new admin-only
      GET /reports/top-reported?days=7&limit=5, closes T060's "top 5 most-reported" gap —
      NOT a reuse of /reports/queue, which answers a different question per T060's own audit)_

---

## Phase 18 — Mandatory Profile Setup (Bonus, not part of the original 96-task list)

Discovered while reviewing T063 (2026-10-29): REQUIREMENTS.md §1 defines onboarding as four
stages (Google sign-in, OTP, **mandatory** profile setup, *optional* identity verification), but
no Phase 11 ticket (T062–T072) ever built the mandatory profile-setup screen (Figma Screen 04) —
T070 ("Profile + Settings") only covers editing an already-complete profile. Confirmed against
`User.IsProfileComplete()`/`TryCompleteOnboarding()` in user-service: without name + photo +
preferred language + a real gender, `IsOnboardingComplete` never flips true and the user never
appears in Discovery. Tech Lead decision: ship this as its own ticket now, ahead of T064, rather
than fold it into T070 or silently let T064 ship first with no way for a new user to ever reach
it. Identity Verification (Figma Screen 05) is correctly optional by design and is deferred —
tracked only as a non-blocking follow-up, not a ticket, for now.

- [ ] T109 — Profile Setup (Onboarding) _(ProfileSetupComponent, mandatory name/photo/language/gender
      step between OTP and the rest of the app; first screen to wire AuthGuard; reuses auth-shared.css)_

T064's dependency updated in SPECS.md to add T109 (Radar is the first screen past this one).

---

## Phase 19 — Optional Identity Verification (Bonus, not part of the original 96-task list)

Unlike Phase 18, this is NOT a blocker for Discovery — `identity_badge` is tracked separately
from `IsOnboardingComplete` and nothing downstream depends on it. Sequenced last, after every
other Phase 11/18 ticket, rather than inserted early. Backend (T019) has existed since Phase 3
with no frontend ever calling it.

- [ ] T110 — Identity Verification (Selfie/Liveness Badge) _(IdentityVerificationComponent, reached
      from Profile/Settings (T070), synchronous POST /users/me/verify-identity — no polling, the
      openapi spec's separate GET /identity/status doesn't actually exist in the shipped controller)_

---

## Post-ship fixes — not part of the original 96-task list

- **[Fixed 2026-09-04] Stale JWT `status` claim on automatic onboarding transition.**
  T018 (Profile CRUD) introduced the rule that `PATCH /users/me` reissues a
  fresh access token whenever `gender`/`status` changes, because both are
  baked into the JWT as claims. T017 (Phone OTP Verification) shipped
  *before* that rule existed: `OtpService.VerifyOtpAsync` also flips `Status`
  from `incomplete` to `active` (via `IsProfileComplete()`) but never
  reissued a token, leaving the caller's existing token claiming
  `status=incomplete` for up to `Jwt:AccessTokenMinutes` after their status
  actually changed. Fixed by giving `OtpService` the same previous/new
  comparison + conditional `IJwtIssuer.IssueAccessToken(user)` reissue
  `UserProfileController` already had, surfaced as a new optional
  `access_token` field on `POST /auth/otp/verify`'s response (same
  present-only-when-changed contract as `PATCH /users/me`). Not folded into
  T017's ticket file (shipped tickets are historical record, not edited
  retroactively) — tracked here instead, discovered during T018 review.

- **[Fixed 2026-09-04] Profile photo URL had no matching api-gateway route.**
  `POST /users/me/photo` builds its returned `photo_url` from the caller's
  own request host plus `ProfilePhotoOptions.BaseUrl` ("/uploads/photos").
  That resolves fine hitting `user-service` directly (local dev's exposed
  port, and this ticket's own QA checklist), but api-gateway's YARP config
  only proxies specific prefixes (`/user/{**catch-all}`, `/discovery/...`,
  etc.) — a bare `/uploads/photos/...` path matches none of them, so a real
  client going through the gateway (the only path that's public in a real
  deployment) would 404 fetching its own photo. Fixed by changing
  `BaseUrl` to `/user/uploads/photos` — no route or static-file-serving
  change needed, since every other endpoint in this service is already
  defined un-prefixed internally and only reachable externally via the
  gateway's `PathRemovePrefix: /user` transform on its existing
  `/user/{**catch-all}` route.

- **[Fixed 2026-09-04] `Status` silently double-booked as onboarding state
  AND availability toggle.** `OtpService` (T017) auto-advances `Status` from
  `incomplete` to `active` once onboarding's conditions hold; `PATCH
  /users/me` (T018) also writes to that exact same column with a completely
  different value set (`looking`/`unavailable`). Because both writers shared
  one column, the first time any onboarded user touched their availability
  toggle, that PATCH silently overwrote and permanently destroyed the
  `active` signal — nothing in the codebase would ever restore it, and no
  acceptance test caught it since every test exercises one writer at a time,
  never the interaction between them. Fixed by splitting the concerns: a new
  `IsOnboardingComplete` boolean (own EF Core migration), written only by
  `OtpService`, carries the onboarding-saga signal — this is also what T020's
  `user.verified` Kafka producer and Discovery Service's scan-eligibility
  check will key off of; `Status` now means only the user-owned availability
  toggle. `POST /auth/otp/verify`'s response field and the JWT's
  informational claim both renamed from `status`/`"active"` to
  `onboarding_complete`/`true` to match.

## Backlog — not ticketed, revisit after all 96 tasks are done

- **Metrics dashboard (Prometheus + Grafana).** `REQUIREMENTS.md`'s tech table lists
  `Micrometer + OpenTelemetry` under Observability, but no task in this list wires
  Micrometer's metrics to anything. T087–T090 (Phase 13) only cover **traces** (Jaeger)
  and **structured logs** (JSON to stdout) — request rates, latency percentiles, and
  error-rate-over-time dashboards are not covered anywhere. Candidate future ticket:
  scrape `/actuator/prometheus` (Java) and `/metrics` (.NET, via
  `OpenTelemetry.Exporter.Prometheus.AspNetCore` or similar) with a Prometheus
  container, visualize in Grafana — same "off-the-shelf, not custom-built" approach
  already used for Jaeger in T089. Deliberately deferred until the full 96-task list
  is done rather than bolted on mid-phase.

- **Distributed rate limiting + volumetric flood protection.** The gateway's
  RateLimitPartitionRegistry (T013) is in-memory, correct only for a single instance.
  Scaling to N gateway replicas silently multiplies every per-IP/per-user/per-phone
  ceiling by N (no shared counter store). Separately, app-level rate limiting runs
  inside Kestrel's pipeline, so it cannot stop a raw connection/volumetric flood —
  that needs a cloud LB/WAF or reverse proxy in front of the gateway. Candidate
  future ticket: Redis-backed distributed limiter + note on upstream L4/L7 protection
  once the gateway is deployed behind a real load balancer.

- **Prod schema-migration strategy is undecided.** Every Java service currently relies on
  Spring Boot's default behavior: Flyway auto-migrates on application boot, using the same
  DB credential the app uses for everyday reads/writes. Discovered while running Discovery
  Service locally for T024 — Flyway's own locking makes concurrent-replica boots safe (only
  one instance actually runs a pending migration; the rest see it's already applied and
  skip it), so that specific race isn't a real danger. The open question is different: should
  a service's everyday runtime DB login even *have* DDL rights (CREATE/DROP/ALTER) in prod,
  given that's a materially bigger blast radius than the DML (SELECT/INSERT/UPDATE) it
  actually needs to function? The more conservative alternative — migrations run as a
  separate, deliberate pipeline step with a narrower-scoped, elevated-but-different
  credential, before a new app version receives traffic, with DDL stripped from the app's
  own login entirely — is a real, common production pattern this project hasn't evaluated.
  No ticket in the 96-task list touches deployment topology or credential scoping. Candidate
  future ticket: decide and implement a prod migration strategy across all Java services,
  likely alongside whatever ticket first defines real (non-Docker-Compose) deployment
  infrastructure.

- **SQL Server connection-pool exhaustion resilience (.NET services).** Every .NET
  service's `AppDbContext` is registered via plain `AddDbContext` + `UseSqlServer(...)`
  (e.g. `user-service/Program.cs`), using ADO.NET's default connection pool (`Max Pool
  Size=100`, `Connection Timeout=15s`) with no tuning, no `EnableRetryOnFailure()`, and
  no monitoring. Under sustained load or a slow/locked query, the pool can exhaust: new
  requests queue for a free connection, hit the 15s timeout, and throw
  `SqlException: Timeout expired...` straight up through the scoped repository/service/
  controller chain — with nothing to retry transient waits or fail fast on purpose. Java
  services have an analogous protection already (Resilience4j circuit breaker around
  Discovery Service's calls to User Service, T027) but nothing plays that role for a
  .NET service's own database connections. Candidate future ticket: add
  `EnableRetryOnFailure()` (EF Core's built-in transient-fault retry, the .NET-side
  equivalent of the Resilience4j Decorator pattern already named in CLAUDE.md) across
  all .NET services' `DbContext` registration, right-size `Max Pool Size`/
  `Connection Timeout` per service based on measured concurrent DB load, and decide
  whether pool-timeout failures should fail fast with a clear error vs. queue. Deliberately
  deferred until the full 96-task list is done, same reasoning as the other entries above.

---

## Summary

**Done:** 35 / 96  
**In Progress:** 0 / 96  
**Pending:** 61 / 96  

_Update this section manually or via `/status` after each task completes._
