# Task Progress

> **How to use:** Mark a task `[x]` as soon as `/implement T00X` completes and all acceptance criteria pass.  
> Start each session with `/status` to get the current state. Always implement the lowest-numbered unchecked task whose dependencies are all checked.

---

## Phase 0 — Foundation & Contracts

- [x] T001 — Monorepo Scaffold _(Root)_
- [x] T002 — Kafka Topic + Contract Definitions _(contracts)_
- [ ] T003 — Shared Error Envelope Contract _(contracts + shared-java-lib)_
- [ ] T004 — Docker Compose Infrastructure Services _(infra)_
- [ ] T005 — OpenAPI Specs — All Services _(contracts/openapi)_

---

## Phase 1 — Infrastructure

- [ ] T006 — SQL Server Database Schemas _(Flyway migrations)_
- [ ] T007 — MongoDB Setup _(chat_db + report_db init scripts)_
- [ ] T008 — Redis Setup _(config — DB 0 discovery cache, DB 1 SignalR backplane)_
- [ ] T009 — Kafka Topics Init _(7 topics, partitions, retention)_
- [ ] T010 — Scoop + JDK 21 Install Script _(dev-setup.ps1)_

---

## Phase 2 — API Gateway (.NET)

- [ ] T011 — YARP Gateway Project Setup
- [ ] T012 — JWT Validation Middleware _(Google JWKS, header injection)_
- [ ] T013 — Rate Limiting Middleware _(100/min global, 10/min connections)_
- [ ] T014 — Gateway Docker Image

---

## Phase 3 — User Service (.NET)

- [ ] T015 — User Service Project + EF Core Setup
- [ ] T016 — Google OAuth + JWT Issuance
- [ ] T017 — Phone OTP Verification _(Twilio, lockout after 5 attempts)_
- [ ] T018 — Profile CRUD _(GET/PATCH /users/me, photo upload)_
- [ ] T019 — Identity Verification Badge _(Azure Face API, NO_BASE_PHOTO guard)_
- [ ] T020 — Kafka Producer: user.verified

---

## Phase 4 — Discovery Service (Java)

- [ ] T021 — Spring Boot Project Setup
- [ ] T022 — Geospatial Schema + Scan Session _(location in Redis only, never SQL)_
- [ ] T023 — GPS Discovery Query _(5 filters, women-only, WebClient to User Service)_
- [ ] T024 — BLE Token Generation _(HMAC-SHA256, 5-min expiry, resolve endpoint)_
- [ ] T025 — Kafka Consumer: user.verified + trust.score.updated
- [ ] T026 — Redis Caching Layer _(DiscoveryCacheService, 10s TTL)_
- [ ] T027 — Resilience4j Circuit Breaker _(User Service calls, 50% threshold, 30s wait)_
- [ ] T028 — Discovery Service Docker Image

---

## Phase 5 — Connection Service (Java)

- [ ] T029 — Spring Boot Setup + DB Schema
- [ ] T030 — Request Lifecycle Endpoints _(PENDING→ACCEPTED/DECLINED/EXPIRED state machine)_
- [ ] T031 — Request TTL Expiry Job _(@Scheduled every 1 min)_
- [ ] T032 — Kafka Producer: connection.accepted + connection.expired
- [ ] T033 — Kafka Consumer: trust.score.updated _(Caffeine cache for request limits)_
- [ ] T034 — Connection Service Docker Image

---

## Phase 6 — Chat Service (.NET)

- [ ] T035 — Chat Service Setup + MongoDB _(TTL index on messages.sent_at)_
- [ ] T036 — SignalR Hub + Real-time Messaging _(Redis backplane DB 1)_
- [ ] T037 — Chat Lifecycle _(OPEN→CLOSING→CLOSED, 5-min grace, 2h auto-close)_
- [ ] T038 — Kafka Consumer: connection.accepted _(open chat room)_
- [ ] T039 — Kafka Producer: chat.closed
- [ ] T040 — Chat Service Docker Image

---

## Phase 7 — Rating Service (Java)

- [ ] T041 — Spring Boot Setup + DB Schema
- [ ] T042 — Rating Submission Endpoint _(tag validation, duplicate check)_
- [ ] T043 — Trust Score Algorithm _(TrustScoreCalculator — pure function, no DB/Kafka)_
- [ ] T044 — Kafka Consumer + Producer _(chat.closed → store; rating.submitted → recalculate → trust.score.updated)_
- [ ] T045 — Trust Score Badges + Throttle Enforcement _(badge ≥4.5+10 ratings, suspended <2.5)_
- [ ] T046 — Rating Service Docker Image

---

## Phase 8 — Notification Service (.NET)

- [ ] T047 — FCM Setup _(Firebase Admin SDK)_
- [ ] T048 — SignalR Notification Hub _(Redis backplane DB 1, shared with Chat)_
- [ ] T049 — Kafka Consumers _(connection.accepted, connection.expired, chat.closed)_
- [ ] T050 — Notification Service Docker Image

---

## Phase 9 — Report Service (Java)

- [ ] T051 — Spring Boot + Spring Data MongoDB Setup
- [ ] T052 — Report Submission _(reason categories, store to MongoDB)_
- [ ] T053 — Escalation Threshold Logic _(count reports per target, threshold trigger)_
- [ ] T054 — Kafka Producer: report.filed _(with DLQ on failure)_
- [ ] T055 — Report Service Docker Image

---

## Phase 10 — Admin Service (Java)

- [ ] T056 — Spring Boot + Spring Security Setup _(OncePerRequestFilter reads X-User-Role)_
- [ ] T057 — Review Queue Endpoints _(sorted by report count desc)_
- [ ] T058 — Trust Score Override _(admin writes directly to ratings_db)_
- [ ] T059 — Account Suspend _(status flag in users_db, re-publishes trust.score.updated)_
- [ ] T060 — Dashboard Queries _(Caffeine-cached stats, 60s TTL)_
- [ ] T061 — Admin Service Docker Image

---

## Phase 11 — Angular Frontend

- [ ] T062 — Angular PWA Setup _(ngsw-config, service worker, Lighthouse ≥90)_
- [ ] T063 — Auth Flow _(Google GSI + OTP, AuthGuard, AuthInterceptor)_
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

## Summary

**Done:** 0 / 96  
**In Progress:** 0 / 96  
**Pending:** 96 / 96  

_Update this section manually or via `/status` after each task completes._
