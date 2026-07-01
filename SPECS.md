# SPECS.md — ShareConnectSave Task Decomposition

## How This Works

1. **You review each task spec** — change scope, add constraints, or mark tasks as skip.
2. **Each task is a standalone subagent job** — self-contained enough that an agent can implement it given only this spec + REQUIREMENTS.md + CLAUDE.md.
3. **Dependencies are explicit** — a task cannot start until all its `Depends on` tasks are complete.
4. **Acceptance criteria are the contract** — a task is done only when every checkbox passes.

Start implementation by saying: `implement T001` (or any task ID). Agents will be spawned per task.

---

## Phase Overview

```
Phase 0: Foundation & Contracts         (T001–T005)
Phase 1: Infrastructure                 (T006–T010)
Phase 2: API Gateway                    (T011–T014)
Phase 3: User Service (.NET)            (T015–T020)
Phase 4: Discovery Service (Java)       (T021–T028)
Phase 5: Connection Service (Java)      (T029–T034)
Phase 6: Chat Service (.NET)            (T035–T040)
Phase 7: Rating Service (Java)          (T041–T046)
Phase 8: Notification Service (.NET)    (T047–T050)
Phase 9: Report Service (Java)          (T051–T055)
Phase 10: Admin Service (Java)          (T056–T061)
Phase 11: Angular Frontend              (T062–T072)
Phase 12: Testing                       (T081–T086)
Phase 13: Observability                 (T087–T090)
Phase 14: Saga + Outbox                 (T091–T096)
```

---

## Phase 0 — Foundation & Contracts

### T001 — Monorepo Scaffold

**Service:** Root  
**Language:** N/A  
**Depends on:** none  
**Skills/Commands:** `mkdir`, `dotnet new`, `mvn archetype`

**Spec:**
Create the top-level folder structure:

```
/ShareConnectSave
  /contracts
    /kafka         ← JSON schema files per topic
    /openapi       ← OpenAPI YAML per service
  /services
    /api-gateway           (.NET 9)
    /user-service          (.NET 9)
    /chat-service          (.NET 9)
    /notification-service  (.NET 9)
    /discovery-service     (Java 21 / Spring Boot 3)
    /connection-service    (Java 21 / Spring Boot 3)
    /rating-service        (Java 21 / Spring Boot 3)
    /report-service        (Java 21 / Spring Boot 3)
    /admin-service         (Java 21 / Spring Boot 3)
  /frontend                (Angular 17)
  /infra
    docker-compose.yml
    docker-compose.override.yml   ← dev overrides (hot reload, volume mounts)
  .editorconfig
  .gitignore
```

Initialize each service as an empty project:
- .NET services: `dotnet new web -n <service-name>`
- Java services: Spring Initializr with Spring Web, Spring Data JPA, Spring Kafka, Validation, Actuator, Lombok
- Angular: `ng new frontend --routing --style=scss`

**Acceptance criteria:**
- [ ] All service folders exist with a compiling empty project
- [ ] `docker-compose.yml` has a stub entry for every service (image: to be built, healthcheck placeholder)
- [ ] `.gitignore` covers .NET, Java, Node, Angular, Docker artifacts
- [ ] `.editorconfig` enforces 4-space indent for Java/C#, 2-space for TypeScript/HTML

---

### T002 — Kafka Topic + Contract Definitions

**Service:** `/contracts`  
**Language:** JSON  
**Depends on:** T001  
**Skills/Commands:** none (file authoring only)

**Spec:**
Create one JSON schema file per Kafka topic in `/contracts/kafka/`. Each file defines the message shape. Use JSON Schema draft-07.

Topics to define (see REQUIREMENTS.md for fields):
- `user.verified.schema.json`
- `connection.accepted.schema.json`
- `connection.expired.schema.json`
- `chat.closed.schema.json`
- `rating.submitted.schema.json`
- `trust.score.updated.schema.json`
- `report.filed.schema.json`

Also create `/contracts/kafka/README.md` documenting topic names, partitions (default 3), retention (7 days), and which services produce/consume each.

**Acceptance criteria:**
- [ ] All 7 schema files exist and are valid JSON Schema
- [ ] Every required field from REQUIREMENTS.md integration contracts table is present
- [ ] README lists producer and consumer for every topic

---

### T003 — Shared Error Envelope Contract

**Service:** `/contracts`  
**Language:** JSON + C# + Java  
**Depends on:** T001  
**Skills/Commands:** none

**Spec:**
Define the shared HTTP error response shape used by all services.

1. Create `/contracts/openapi/error-envelope.yaml` — OpenAPI component schema for `ErrorResponse`.
2. Create `/services/user-service/src/Contracts/ErrorResponse.cs` as a C# record (copy this pattern to all .NET services in their respective tasks).
3. Create a shared Java library at `/services/shared-java-lib/` — a minimal Maven module with `ErrorResponse.java` and `GlobalExceptionHandler.java` (`@ControllerAdvice`). All Java services will depend on this module.

**Acceptance criteria:**
- [ ] `ErrorResponse` has fields: `code` (string), `message` (string), `traceId` (string)
- [ ] Java `GlobalExceptionHandler` maps `MethodArgumentNotValidException` → 400 with code `VALIDATION_ERROR`
- [ ] Java `GlobalExceptionHandler` maps unhandled `Exception` → 500 with code `INTERNAL_ERROR`
- [ ] C# record matches same JSON shape

---

### T004 — Docker Compose Infrastructure Services

**Service:** `/infra`  
**Language:** YAML  
**Depends on:** T001  
**Skills/Commands:** `docker compose up`

**Spec:**
Define all infrastructure dependencies in `docker-compose.yml`:

- **SQL Server 2022** — single instance, each service gets its own database (not schema) created on first run
- **MongoDB 7** — single instance, databases: `chat_db`, `report_db`
- **Kafka (Bitnami)** + Zookeeper — single broker for dev
- **Redis 7** — used by Discovery Service (cache) and SignalR backplane
- **Kafka UI** (provectuslabs/kafka-ui) — dev convenience, port 9000

All infra services have healthchecks. Application service stubs reference infra by container name (see REQUIREMENTS.md service URL table).

**Acceptance criteria:**
- [ ] `docker compose up` starts all infra without errors
- [ ] SQL Server reachable at `localhost:1433`
- [ ] MongoDB reachable at `localhost:27017`
- [ ] Kafka broker reachable at `localhost:9092`
- [ ] Redis reachable at `localhost:6379`
- [ ] Kafka UI accessible at `http://localhost:9000`

---

### T005 — OpenAPI Specs (All Services)

**Service:** `/contracts/openapi`  
**Language:** YAML  
**Depends on:** T002, T003  
**Skills/Commands:** none

**Spec:**
Author one OpenAPI 3.1 YAML file per service. These are the contracts subagents implement against.

Files:
- `user-service.yaml`
- `discovery-service.yaml`
- `connection-service.yaml`
- `chat-service.yaml`
- `rating-service.yaml`
- `notification-service.yaml`
- `report-service.yaml`
- `admin-service.yaml`

Each file must cover all endpoints from REQUIREMENTS.md for that service. Include:
- Request/response schemas (reference `error-envelope.yaml` for errors)
- Auth note: "JWT validated at gateway; services receive X-User-Id header"
- All HTTP status codes (200/201/400/401/403/404/409/500)

**Acceptance criteria:**
- [ ] All 8 YAML files exist and validate against OpenAPI 3.1 spec
- [ ] Every endpoint from REQUIREMENTS.md is represented
- [ ] Error responses reference the shared `ErrorResponse` component

---

## Phase 1 — Infrastructure

### T006 — SQL Server Database Schemas

**Service:** All relational services  
**Language:** SQL + Flyway migrations  
**Depends on:** T004  
**Skills/Commands:** Flyway

**Spec:**
Create Flyway migration scripts for each service that uses SQL Server. Each service gets its own database. Place migrations at `services/<service>/src/main/resources/db/migration/` (Java) or `services/<service>/Migrations/` (.NET EF Core).

**User Service DB (`users_db`):**
- `users` — id, google_id, phone, name, photo_url, gender, preferred_language, status, created_at
- `otp_attempts` — id, phone, attempt_count, locked_until
- `identity_verifications` — id, user_id, status, verified_at

**Discovery Service DB (`discovery_db`):**
- `scan_sessions` — id, user_id, destination_lat, destination_lng, destination_label, departure_time, started_at, ended_at
- `ble_tokens` — id, user_id, token_hash, expires_at

**Connection Service DB (`connections_db`):**
- `connection_requests` — id, requester_id, recipient_id, status, created_at, expires_at, updated_at

**Rating Service DB (`ratings_db`):**
- `ratings` — id, rater_id, rated_id, connection_id, tags (JSON array), created_at
- `trust_scores` — id, user_id, score, badge_level, request_limit, updated_at

**Report Service DB (`reports_db` in MongoDB — handled in T051)**

**Admin Service DB:** reads from `users_db`, `ratings_db`, `reports_db` — no own schema

**Acceptance criteria:**
- [ ] Migrations run cleanly on fresh SQL Server instance
- [ ] All tables have appropriate indexes (user_id FK, status, created_at)
- [ ] Geospatial columns on `scan_sessions` use SQL Server `geography` type for destination point

---

### T007 — MongoDB Setup

**Service:** Chat Service, Report Service  
**Language:** YAML + init scripts  
**Depends on:** T004  
**Skills/Commands:** Docker, MongoDB

**Spec:**
Create MongoDB initialization scripts in `/infra/mongo-init/`:

**`chat_db`:**
- Collection: `messages` — fields: `_id`, `chat_id`, `sender_id`, `content`, `sent_at`, TTL index on `sent_at` (2 hours default, configurable)
- Collection: `chat_rooms` — fields: `_id`, `connection_id`, `user_a_id`, `user_b_id`, `status`, `created_at`, `closed_at`

**`report_db`:**
- Collection: `reports` — fields: `_id`, `reporter_id`, `reported_id`, `reason`, `context` (free text), `filed_at`, `status`

Both collections include a `createIndexes` call in the init script.

**Acceptance criteria:**
- [ ] Init script runs on `docker compose up` via `mongo-init` volume mount
- [ ] TTL index on `messages.sent_at` is created and `expireAfterSeconds` is set
- [ ] Collections have compound indexes on `chat_id` and `user_id` fields

---

### T008 — Redis Setup

**Service:** Discovery Service (cache), Chat + Notification Services (SignalR backplane)  
**Language:** YAML  
**Depends on:** T004  
**Skills/Commands:** Docker

**Spec:**
Redis is already in Docker Compose (T004). This task configures logical database separation:
- DB 0: Discovery Service (nearby scan cache)
- DB 1: SignalR backplane (Chat + Notification services)

Create `/infra/redis.conf` with:
- `maxmemory 256mb`
- `maxmemory-policy allkeys-lru`
- No persistence (dev) — `save ""`

Document the DB index convention in `/infra/README.md`.

**Acceptance criteria:**
- [ ] Redis starts with the config file mounted
- [ ] `redis-cli ping` returns PONG
- [ ] DB separation documented

---

### T009 — Kafka Topics Init

**Service:** Kafka  
**Language:** Shell / Docker  
**Depends on:** T004  
**Skills/Commands:** `kafka-topics.sh`

**Spec:**
Create an init script `/infra/kafka-init.sh` that creates all topics defined in T002:

```
user.verified          — partitions: 3, replication: 1
connection.accepted    — partitions: 3, replication: 1
connection.expired     — partitions: 3, replication: 1
chat.closed            — partitions: 3, replication: 1
rating.submitted       — partitions: 3, replication: 1
trust.score.updated    — partitions: 3, replication: 1
report.filed           — partitions: 3, replication: 1
```

Wire this script as a `kafka-init` service in Docker Compose that runs after Kafka is healthy.

**Acceptance criteria:**
- [ ] All 7 topics visible in Kafka UI after `docker compose up`
- [ ] Init service exits 0 after creating topics
- [ ] Topics are idempotent (re-running script on existing topics doesn't error)

---

### T010 — Scoop + JDK 21 Install Script

**Service:** Developer environment  
**Language:** PowerShell  
**Depends on:** none  
**Skills/Commands:** Scoop

**Spec:**
Create `/dev-setup.ps1` — a one-shot developer environment setup script for Windows (no admin required):

```powershell
# Installs: Scoop, JDK 21, IntelliJ IDEA Community, .NET 9 SDK check, Node 20, Docker Desktop check
```

Script should:
1. Install Scoop if not present
2. Add `java` and `extras` buckets
3. Install `temurin21-jdk` via Scoop
4. Install `intellij-idea-community-edition` via Scoop
5. Check `dotnet --version` (>= 9) and warn if missing
6. Check `node --version` (>= 20) and warn if missing
7. Check `docker --version` and warn if missing
8. Print a summary of what was installed vs already present

**Acceptance criteria:**
- [ ] Script runs without admin on Windows 11
- [ ] After running: `java -version` shows 21, `javac -version` shows 21
- [ ] Script is idempotent — re-running doesn't reinstall

---

## Phase 2 — API Gateway

### T011 — YARP Gateway Project Setup

**Service:** `api-gateway`  
**Language:** .NET 9  
**Depends on:** T001, T003  
**Skills/Commands:** `dotnet run`

**Spec:**
Set up the YARP reverse proxy as the single entry point. Install `Yarp.ReverseProxy` NuGet package.

Configure `appsettings.json` routes to all 8 downstream services (use container names as defined in REQUIREMENTS.md). Each route pattern: `/<service-prefix>/{**catch-all}`.

**Acceptance criteria:**
- [ ] Gateway starts and proxies a request to a running downstream stub
- [ ] Unknown routes return 404

---

### T012 — JWT Validation Middleware

**Service:** `api-gateway`  
**Language:** .NET 9  
**Depends on:** T011  
**Skills/Commands:** none

**Spec:**
Add JWT Bearer authentication middleware. Validate:
- Signature (Google's JWKS endpoint for the Google Sign-In tokens)
- Expiry
- Audience (your app's client ID from `process.env.GOOGLE_CLIENT_ID`)

On valid JWT: extract `sub` (user ID), `role`, `gender` claims. Inject as headers before forwarding:
```
X-User-Id: <sub>
X-User-Role: <role>
X-User-Gender: <gender>
```

Strip the `Authorization` header before forwarding downstream (services must not receive the raw JWT).

Public routes (no JWT required): `POST /auth/google`, `POST /auth/otp/send`, `POST /auth/otp/verify`.

**Acceptance criteria:**
- [ ] Request with valid JWT → headers injected, Authorization stripped, proxied
- [ ] Request with expired/invalid JWT → 401 returned, not forwarded
- [ ] Public routes pass through without JWT

---

### T013 — Rate Limiting Middleware

**Service:** `api-gateway`  
**Language:** .NET 9  
**Depends on:** T012  
**Skills/Commands:** none

**Spec:**
Use .NET 9 built-in rate limiting (`Microsoft.AspNetCore.RateLimiting`).

Policies:
- **Global:** 100 req/min per IP (sliding window)
- **Connection requests:** 10 req/min per `X-User-Id` on `POST /connections` (fixed window)
- **OTP:** 5 req/10min per phone number on `POST /auth/otp/send`

429 responses use the shared error envelope: `{ "code": "RATE_LIMIT_EXCEEDED", ... }`.

**Acceptance criteria:**
- [ ] Exceeding global limit returns 429
- [ ] Exceeding connection limit returns 429 with `RATE_LIMIT_EXCEEDED`
- [ ] Rate limit headers (`X-RateLimit-Remaining`) present on all responses

---

### T014 — Gateway Docker Image

**Service:** `api-gateway`  
**Language:** Dockerfile  
**Depends on:** T013  
**Skills/Commands:** `docker build`

**Spec:**
Multi-stage Dockerfile:
1. `build` stage: `mcr.microsoft.com/dotnet/sdk:9.0`
2. `runtime` stage: `mcr.microsoft.com/dotnet/aspnet:9.0`

Wire into `docker-compose.yml`. Add healthcheck: `GET /health` → 200.

**Acceptance criteria:**
- [ ] `docker compose up api-gateway` starts without error
- [ ] Healthcheck passes
- [ ] Image size under 200 MB

---

## Phase 3 — User Service (.NET)

### T015 — User Service Project + EF Core Setup

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T001, T003, T006  
**Skills/Commands:** `dotnet ef migrations add`

**Spec:**
Set up the User Service with:
- EF Core + SQL Server provider
- `UsersDbContext` with entities matching T006 schema
- EF Core migrations (keep in sync with Flyway scripts from T006 — pick one approach, document it)
- `IUserRepository`, `IOtpRepository`, `IIdentityVerificationRepository` interfaces + EF implementations
- Health endpoint: `GET /health`

Use `process.env.*` for all connection strings. Never hardcode.

**Acceptance criteria:**
- [ ] `dotnet ef database update` creates all tables
- [ ] `GET /health` returns 200
- [ ] Service starts connected to SQL Server container

---

### T016 — Google OAuth + JWT Issuance

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T015  
**Skills/Commands:** none

**Spec:**
`POST /auth/google`  
Body: `{ "id_token": "<google id token>" }`

1. Validate the Google ID token using Google's token info endpoint.
2. Extract `sub`, `email`, `name`, `picture`.
3. Upsert user in `users` table.
4. Issue a signed JWT with claims: `sub` = user_id (our UUID), `role` = "user", `gender` (from profile if set).
5. Return: `{ "access_token": "...", "refresh_token": "...", "is_new_user": true/false }`

New users (`is_new_user: true`) must complete profile setup before discovery is enabled. Set `status = incomplete` until phone is verified and gender is set.

**Acceptance criteria:**
- [ ] Valid Google token → JWT issued, user upserted
- [ ] Invalid Google token → 401 with `INVALID_GOOGLE_TOKEN`
- [ ] Returned JWT validates against the gateway's JWKS config

---

### T017 — Phone OTP Verification

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T016  
**Skills/Commands:** none

**Spec:**
`POST /auth/otp/send` — sends OTP to phone number (use Twilio via `process.env.TWILIO_*`).  
`POST /auth/otp/verify` — verifies OTP, marks phone as verified on user record.

Brute-force protection: after 5 failed attempts within 10 minutes, lock the phone number for 15 minutes. Track in `otp_attempts` table.

On successful verification: update user `status` to `active` if profile is also complete.

**Acceptance criteria:**
- [ ] Correct OTP → phone marked verified, user status updated
- [ ] Wrong OTP → 400 with `INVALID_OTP`, attempt count incremented
- [ ] After 5 failures → 429 with `OTP_LOCKED`, lockout time in response

---

### T018 — Profile CRUD

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T016  
**Skills/Commands:** none

**Spec:**
- `GET /users/me` — returns own profile (all fields)
- `PATCH /users/me` — update name, preferred_language, gender, status (`looking` | `unavailable`)
- `POST /users/me/photo` — multipart upload. Stores photo, sets `photo_url`. Required before identity verification can be started. If Google provided a photo on sign-in, this is pre-populated but user can replace it.
- `GET /users/:id` — returns public profile (name, photo, rating, badges, destination — no gender, no phone)

**Acceptance criteria:**
- [ ] `PATCH` validates gender is one of `female | male | unspecified`
- [ ] `GET /users/:id` never returns gender or phone fields
- [ ] `PATCH` with invalid status value returns 400
- [ ] `POST /users/me/photo` sets `photo_url`, overwrites any Google-provided photo
- [ ] After `POST /users/me/photo`, `GET /users/me` reflects the new `photo_url`

---

### T019 — Identity Verification Badge

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T018  
**Skills/Commands:** none

**Spec:**
`POST /users/me/verify-identity`  
Body: multipart form — `selfie` (image file)

Pre-condition check: user must have a `photo_url` set on their profile before this endpoint can be called.
- If `photo_url` is null (Google account had no photo and user hasn't uploaded one): return 422 with `NO_BASE_PHOTO` and a message directing them to upload a profile photo first via `PATCH /users/me`.
- If `photo_url` is set: proceed with face match.

Steps:
1. Download the base photo from `photo_url`.
2. Compare selfie against base photo using Azure Face API (`process.env.AZURE_FACE_*`) or fallback stub returning `verified: true` for dev.
3. On match (confidence > 0.8): create `identity_verifications` record with `status = verified`, set `identity_badge = true` on user.
4. On mismatch: record `status = failed`, return 422 with `IDENTITY_MISMATCH`.

**Acceptance criteria:**
- [ ] No `photo_url` on profile → 422 with `NO_BASE_PHOTO` before any face API call
- [ ] Matching faces → badge set on user, 200 returned
- [ ] Confidence below threshold → 422 with `IDENTITY_MISMATCH`
- [ ] Dev mode (env flag `IDENTITY_VERIFY_STUB=true`) always returns verified
- [ ] User who uploads photo via `PATCH /users/me` can then call this endpoint successfully

---

### T020 — Kafka Producer: user.verified

**Service:** `user-service`  
**Language:** .NET 9  
**Depends on:** T017, T018, T009  
**Skills/Commands:** none

**Spec:**
When a user's status transitions to `active` (phone verified + profile complete), publish a `user.verified` event to Kafka.

Use `Confluent.Kafka` NuGet. Message schema from T002. Produce to topic `user.verified` with `user_id` as the partition key.

**Acceptance criteria:**
- [ ] Status transition to `active` → event visible in Kafka UI
- [ ] Event matches `user.verified.schema.json` contract
- [ ] Kafka unavailable → log error, do not fail the HTTP request (fire-and-forget with retry in background)

---

## Phase 4 — Discovery Service (Java)

### T021 — Spring Boot Project Setup

**Service:** `discovery-service`  
**Language:** Java 21 / Spring Boot 3  
**Depends on:** T001, T008, T009  
**Skills/Commands:** `mvn spring-boot:run`

**Spec:**
Initialize with: Spring Web, Spring Data JPA, Spring Kafka, Spring Data Redis, Validation, Actuator, Lombok, MapStruct, Flyway, Resilience4j.

Set up:
- `application.yml` with profiles: `dev`, `prod`
- `DataSource` config via `process.env.DISCOVERY_DB_*`
- Redis config via `process.env.REDIS_URL`
- Flyway migration location: `classpath:db/migration`
- `GET /actuator/health` → 200
- `GlobalExceptionHandler` from shared-java-lib (T003)

**Acceptance criteria:**
- [ ] Service starts and connects to SQL Server + Redis
- [ ] `/actuator/health` returns `{ "status": "UP" }`
- [ ] Flyway migrations run on startup

---

### T022 — Geospatial Schema + Scan Session

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T021, T006  
**Skills/Commands:** none

**Spec:**
Flyway migration creates `scan_sessions` and `ble_tokens` tables (matching T006 schema).

JPA entities: `ScanSession`, `BleToken`.

`POST /scan/start` — authenticated (reads `X-User-Id` header)  
Body: `{ "destination_lat", "destination_lng", "destination_label", "departure_time" }`  
Creates a scan session, returns `{ "session_id", "mode": "gps" }`.

`POST /scan/stop` — closes the session, clears location from Redis.

During an active session, the client sends location updates:  
`PUT /scan/location` — Body: `{ "lat", "lng" }` — stored in Redis only (key: `scan:{session_id}:location`, TTL 30s). Never written to SQL.

**Acceptance criteria:**
- [ ] `POST /scan/start` creates DB record, returns session_id
- [ ] `PUT /scan/location` writes to Redis, not SQL
- [ ] After `POST /scan/stop`, Redis key is deleted
- [ ] Location key TTL is 30 seconds (auto-expires if client goes silent)

---

### T023 — GPS Discovery Query

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T022  
**Skills/Commands:** none

**Spec:**
`GET /scan/nearby` — returns users within the active session's filter criteria.

Query logic (all server-side):
1. Read caller's current location from Redis (`scan:{session_id}:location`).
2. Read all active scan sessions from Redis (key: `scan:active_sessions` — a Redis Set of session IDs populated when sessions start/stop).
3. For each candidate session, apply filters:
   - Distance ≤ radius (default 1 km, configurable per session)
   - Route overlap ≥ threshold (compute bearing similarity between destinations)
   - Departure time within ±30 min
   - Not in caller's block list (fetched from User Service via WebClient, cached in Redis DB 0, TTL 5 min)
   - Candidate status = `looking`
   - Women-only mode: if caller has `X-User-Gender: female` and has women-only enabled, filter to `female` only
4. For each match: call User Service `GET /users/:id` (WebClient, Resilience4j circuit breaker, fallback: return cached profile or omit).
5. Cache full result set in Redis (key: `scan:{session_id}:nearby`, TTL 10s).

Return: array of user cards.

**Acceptance criteria:**
- [ ] Users outside radius are excluded
- [ ] Block list filtering works (bidirectional)
- [ ] Women-only filter excludes non-female users when enabled
- [ ] Result cached in Redis for 10 s
- [ ] User Service circuit breaker: if open, return cached profile data, log warning

---

### T024 — BLE Token Generation

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T022  
**Skills/Commands:** none

**Spec:**
`POST /scan/ble/token` — generates a rotating BLE token for the current user.

1. Generate a random 16-byte token, store its HMAC-SHA256 hash in `ble_tokens` table with `expires_at = now + 5 minutes`.
2. Return the raw token (client broadcasts this over BLE).
3. Old tokens for this user (expired) are deleted.

`POST /scan/ble/resolve` — resolves a detected BLE token to a user profile (called when back online).  
Body: `{ "tokens": ["<raw_token_1>", ...] }`  
Returns: array of user card objects for resolved users (same shape as GPS nearby results).

**Acceptance criteria:**
- [ ] Token hash stored, raw token returned to client
- [ ] Token expires after 5 minutes (DB record AND functional — expired tokens not resolvable)
- [ ] Resolve endpoint returns profile data for valid tokens only
- [ ] Expired or unknown tokens silently omitted from resolve response

---

### T025 — Kafka Consumer: user.verified + trust.score.updated

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T021, T009  
**Skills/Commands:** none

**Spec:**
Two `@KafkaListener` consumers:

1. `user.verified` → add user to Redis Set `scan:eligible_users`. This is the gate — only verified users appear in discovery.
2. `trust.score.updated` → update cached trust data for the user. If `request_limit = 0`, remove from `scan:eligible_users` (suspended users cannot appear in scan).

Use consumer group: `discovery-service`.

**Acceptance criteria:**
- [ ] New verified user → appears in discovery within 1 Kafka poll cycle
- [ ] Trust suspended user → removed from `scan:eligible_users`
- [ ] Consumer group offset committed after processing

---

### T026 — Redis Caching Layer

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T021  
**Skills/Commands:** none

**Spec:**
Centralise all Redis interactions in a `DiscoveryCacheService`. Keys and TTLs:

| Key pattern | TTL | Content |
|---|---|---|
| `scan:{session_id}:location` | 30 s | `{lat, lng}` JSON |
| `scan:{session_id}:nearby` | 10 s | Array of user cards |
| `scan:active_sessions` | — (Set, managed) | Set of active session IDs |
| `scan:eligible_users` | — (Set, managed) | Set of user IDs eligible for discovery |
| `user:{id}:profile_cache` | 5 min | Public profile JSON |
| `user:{id}:blocklist_cache` | 5 min | Array of blocked user IDs |

**Acceptance criteria:**
- [ ] All cache reads/writes go through `DiscoveryCacheService`, not raw `RedisTemplate` calls in controllers
- [ ] Cache miss on profile → WebClient fetch, store result
- [ ] All TTLs configurable via `application.yml`

---

### T027 — Resilience4j Circuit Breaker (User Service calls)

**Service:** `discovery-service`  
**Language:** Java  
**Depends on:** T026  
**Skills/Commands:** none

**Spec:**
Wrap all `WebClient` calls to User Service with a Resilience4j circuit breaker named `userService`.

Config in `application.yml`:
- `slidingWindowSize: 10`
- `failureRateThreshold: 50`
- `waitDurationInOpenState: 30s`
- Fallback: return cached profile from Redis if available, else return a minimal placeholder `{ "id": "<id>", "name": "Unknown", "unavailable": true }`

**Acceptance criteria:**
- [ ] 5 consecutive failures → circuit opens, subsequent calls hit fallback immediately
- [ ] After 30 s → circuit half-opens, next call attempts real request
- [ ] Circuit state visible on `/actuator/health`

---

### T028 — Discovery Service Docker Image

**Service:** `discovery-service`  
**Language:** Dockerfile  
**Depends on:** T027  
**Skills/Commands:** `docker build`

**Spec:**
Multi-stage Dockerfile:
1. `build` stage: `eclipse-temurin:21-jdk` + Maven wrapper
2. `runtime` stage: `eclipse-temurin:21-jre-alpine`

Wire into `docker-compose.yml`. Healthcheck: `GET /actuator/health`.

**Acceptance criteria:**
- [ ] `docker compose up discovery-service` starts and health passes
- [ ] Image size under 300 MB

---

## Phase 5 — Connection Service (Java)

### T029 — Spring Boot Setup + DB Schema

**Service:** `connection-service`  
**Language:** Java 21  
**Depends on:** T001, T006  
**Skills/Commands:** `mvn spring-boot:run`

**Spec:**
Same setup pattern as T021. Dependencies: Spring Web, JPA, Kafka, Validation, Actuator, Lombok, MapStruct, Flyway.

Flyway migration: `connection_requests` table (see T006 schema).

JPA entity: `ConnectionRequest` with `status` as an enum (`PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED`).

**Acceptance criteria:**
- [ ] Service starts, Flyway runs, `/actuator/health` returns UP

---

### T030 — Request Lifecycle Endpoints

**Service:** `connection-service`  
**Language:** Java  
**Depends on:** T029  
**Skills/Commands:** none

**Spec:**
- `POST /connections` — send a request. Body: `{ "recipient_id" }`. Reads sender from `X-User-Id`. Creates record with status `PENDING`, `expires_at = now + 10 min`. Returns `{ "connection_id" }`.
- `POST /connections/:id/accept` — caller must be the recipient. Transitions to `ACCEPTED`.
- `POST /connections/:id/decline` — caller must be the recipient. Transitions to `DECLINED`.
- `GET /connections/pending` — returns caller's pending inbound requests.
- `GET /connections/active` — returns caller's accepted (open) connection.

State machine rules:
- Only `PENDING` → `ACCEPTED` or `DECLINED` transitions are valid.
- A user can only have one active connection at a time.
- Requester cannot have more than `request_limit` pending outbound requests (limit from Trust Service via Kafka, cached locally).

**Acceptance criteria:**
- [ ] Invalid state transition returns 409 with `INVALID_STATE_TRANSITION`
- [ ] Duplicate active connection returns 409 with `ACTIVE_CONNECTION_EXISTS`
- [ ] Non-recipient trying to accept/decline returns 403

---

### T031 — Request TTL Expiry Job

**Service:** `connection-service`  
**Language:** Java  
**Depends on:** T030  
**Skills/Commands:** none

**Spec:**
Scheduled task (`@Scheduled`, runs every 1 minute): find all `PENDING` requests where `expires_at < now`, transition them to `EXPIRED`.

For each expired request, publish `connection.expired` Kafka event.

**Acceptance criteria:**
- [ ] Pending request past `expires_at` → transitioned to `EXPIRED` within 1 minute
- [ ] `connection.expired` event published per expired request
- [ ] Scheduler does not fail if Kafka is temporarily unavailable (log + retry)

---

### T032 — Kafka Producer: connection.accepted + connection.expired

**Service:** `connection-service`  
**Language:** Java  
**Depends on:** T030, T031, T009  
**Skills/Commands:** none

**Spec:**
On `ACCEPTED` transition: publish `connection.accepted` event.  
On `EXPIRED` transition (from scheduler): publish `connection.expired` event.  

Both use `KafkaTemplate`. Message schemas from T002. Partition key: `connection_id`.

**Acceptance criteria:**
- [ ] Acceptance → event in Kafka UI within 500 ms
- [ ] Event schemas match contracts from T002

---

### T033 — Kafka Consumer: trust.score.updated

**Service:** `connection-service`  
**Language:** Java  
**Depends on:** T032  
**Skills/Commands:** none

**Spec:**
Listen to `trust.score.updated`. Cache `request_limit` per `user_id` in a local in-memory map (ConcurrentHashMap, TTL managed manually or via Caffeine cache). Used by T030 to enforce send limits.

**Acceptance criteria:**
- [ ] Updated limit takes effect on next request send attempt (within 1 Kafka poll cycle)

---

### T034 — Connection Service Docker Image

**Service:** `connection-service`  
**Language:** Dockerfile  
**Depends on:** T033  
**Skills/Commands:** `docker build`

Same pattern as T028.

**Acceptance criteria:**
- [ ] `docker compose up connection-service` starts and health passes

---

## Phase 6 — Chat Service (.NET)

### T035 — Chat Service Setup + MongoDB

**Service:** `chat-service`  
**Language:** .NET 9  
**Depends on:** T001, T007  
**Skills/Commands:** `dotnet run`

**Spec:**
Add `MongoDB.Driver` NuGet. Configure via `process.env.MONGO_CONNECTION_STRING`. Repositories: `IChatRoomRepository`, `IMessageRepository`.

Entities match T007 schema. TTL index on `messages.sent_at` is created programmatically on startup (idempotent `CreateIndex` call).

**Acceptance criteria:**
- [ ] Service starts connected to MongoDB
- [ ] TTL index exists on `messages.sent_at` on first run

---

### T036 — SignalR Hub + Real-time Messaging

**Service:** `chat-service`  
**Language:** .NET 9  
**Depends on:** T035  
**Skills/Commands:** none

**Spec:**
`ChatHub : Hub` with methods:
- `JoinRoom(chatId)` — add connection to SignalR group `chat:{chatId}`
- `SendMessage(chatId, content)` — persist to MongoDB, broadcast to group
- `LeaveRoom(chatId)` — remove from group

REST endpoints:
- `GET /chats/:id/messages` — returns last N messages (N configurable, max 50)
- `POST /chats/:id/met` — marks met successfully, triggers chat close flow

SignalR backplane: Redis DB 1 (`Microsoft.AspNetCore.SignalR.StackExchangeRedis`).

**Acceptance criteria:**
- [ ] Two clients in same group receive messages in real-time
- [ ] Messages persisted to MongoDB before broadcast
- [ ] `GET /chats/:id/messages` returns messages sorted ascending by `sent_at`

---

### T037 — Chat Lifecycle (Open / Auto-close)

**Service:** `chat-service`  
**Language:** .NET 9  
**Depends on:** T036  
**Skills/Commands:** none

**Spec:**
Chat room states: `OPEN` → `CLOSING` → `CLOSED`.

- Open: created when `connection.accepted` Kafka event received (T038).
- `POST /chats/:id/met`: transitions to `CLOSING`, starts 5-min grace timer (background `Task.Delay`), then `CLOSED`.
- Auto-close: `IHostedService` polls every 5 minutes, closes rooms open longer than 2 hours.

On close (any path): delete all messages from MongoDB (not just TTL expiry — explicit delete), publish `chat.closed` Kafka event.

**Acceptance criteria:**
- [ ] Met Successfully → room closes after 5-min grace, messages deleted
- [ ] Room open > 2 hours → auto-closed
- [ ] `chat.closed` event published on all close paths
- [ ] Messages are explicitly deleted, not just left to TTL (belt-and-suspenders)

---

### T038 — Kafka Consumer: connection.accepted

**Service:** `chat-service`  
**Language:** .NET 9  
**Depends on:** T037, T009  
**Skills/Commands:** none

**Spec:**
Consume `connection.accepted`. On receipt: create a new `chat_room` document with status `OPEN`, notify both users via SignalR (send `ChatOpened` event to each user's personal SignalR group `user:{id}`).

**Acceptance criteria:**
- [ ] Kafka event → chat room created within 1 poll cycle
- [ ] Both users receive `ChatOpened` SignalR notification

---

### T039 — Kafka Producer: chat.closed

**Service:** `chat-service`  
**Language:** .NET 9  
**Depends on:** T037  
**Skills/Commands:** none

**Spec:**
On chat room close: publish `chat.closed` event using `Confluent.Kafka`. Schema from T002. Partition key: `chat_id`.

**Acceptance criteria:**
- [ ] Chat close → event in Kafka UI
- [ ] Event matches `chat.closed.schema.json` contract

---

### T040 — Chat Service Docker Image

Same pattern as T014. Depends on T039.

---

## Phase 7 — Rating Service (Java)

### T041 — Spring Boot Setup + DB Schema

**Service:** `rating-service`  
**Language:** Java 21  
**Depends on:** T001, T006  

Same setup pattern as T021. Flyway migration: `ratings`, `trust_scores` tables.

---

### T042 — Rating Submission Endpoint

**Service:** `rating-service`  
**Language:** Java  
**Depends on:** T041  

**Spec:**
`POST /ratings`  
Body: `{ "connection_id", "rated_user_id", "tags": ["friendly", "on_time"] }`  
Reads rater from `X-User-Id`.

Validation:
- Tags must be from the allowed set (REQUIREMENTS.md §7).
- Cannot rate the same connection twice.
- `connection_id` must match a closed chat (validate via Kafka-sourced data or HTTP to Connection Service).

Persist to `ratings` table. Publish `rating.submitted` Kafka event.

**Acceptance criteria:**
- [ ] Valid rating → persisted, event published
- [ ] Duplicate rating → 409 with `RATING_ALREADY_SUBMITTED`
- [ ] Invalid tag → 400 with `INVALID_RATING_TAG`

---

### T043 — Trust Score Algorithm

**Service:** `rating-service`  
**Language:** Java  
**Depends on:** T042  

**Spec:**
`TrustScoreCalculator` — pure domain service, no I/O. Inputs: full rating history for a user. Output: `TrustScoreResult { score, badgeLevel, requestLimit }`.

Algorithm:
- Base score: weighted average of last 50 ratings (recent ratings weighted higher).
- Negative tag multipliers: `abusive` and `asked_for_money` count double.
- Badge levels: `none` (< 4.0), `trusted` (≥ 4.5, ≥ 10 ratings).
- Request limits: `unlimited` (score ≥ 3.5), `throttled: 3/day` (2.5–3.5), `suspended: 0` (< 2.5 or ≥ 3 `report.filed` events).

**Acceptance criteria:**
- [ ] Unit tests cover: new user (no ratings), high score, low score, suspension threshold
- [ ] Algorithm is a pure function — no DB calls, fully unit testable

---

### T044 — Kafka Consumer + Producer

**Service:** `rating-service`  
**Language:** Java  
**Depends on:** T043, T009  

**Spec:**
- Consume `chat.closed`: store `{ connection_id, user_a_id, user_b_id }` locally — used to validate rating submissions.
- Consume `rating.submitted` (self-published): recalculate trust score for `rated_user_id`, publish `trust.score.updated`.
- Consume `report.filed`: if report count for target ≥ threshold (configurable, default 3), force recalculate — may push score to suspension.

**Acceptance criteria:**
- [ ] `chat.closed` consumed → connection stored for rating validation
- [ ] `rating.submitted` → score recalculated and `trust.score.updated` published within 2 s
- [ ] 3 reports against a user → trust score recalculated immediately

---

### T045 — Trust Score Badges + Throttle Enforcement

**Service:** `rating-service`  
**Language:** Java  
**Depends on:** T044  

**Spec:**
`GET /trust/:userId` — returns current trust score, badge level, and request limit for a user. Used by Admin Service and Connection Service.

Update `trust_scores` table on every recalculation.

**Acceptance criteria:**
- [ ] Response matches current `trust_scores` table record
- [ ] `trusted` badge only returned when conditions met (≥ 4.5, ≥ 10 ratings)

---

### T046 — Rating Service Docker Image

Same pattern as T028. Depends on T045.

---

## Phase 8 — Notification Service (.NET)

### T047 — FCM Setup

**Service:** `notification-service`  
**Language:** .NET 9  
**Depends on:** T001  

**Spec:**
Integrate Firebase Admin SDK (`FirebaseAdmin` NuGet). Config: `process.env.FCM_CREDENTIALS_JSON` (path to service account JSON).

`IFcmNotificationService` with `SendAsync(userId, title, body, data)` — looks up user's FCM device token (stored in User Service, fetched via HTTP).

**Acceptance criteria:**
- [ ] Service starts and Firebase app initialised
- [ ] `SendAsync` with a real FCM token delivers a notification (smoke test in dev)

---

### T048 — SignalR Notification Hub

**Service:** `notification-service`  
**Language:** .NET 9  
**Depends on:** T047  

**Spec:**
`NotificationHub : Hub`. On connect, users join their personal group `user:{X-User-Id}`.

The service publishes SignalR events to user groups (does not receive from clients). Events:
- `ConnectionRequest` — new inbound request
- `RequestAccepted` — your request was accepted
- `RequestDeclined` — your request was declined
- `RequestExpired` — your request expired
- `ChatOpened` — (also sent by Chat Service, Notification Service deduplicates)

Redis backplane (DB 1) shared with Chat Service.

**Acceptance criteria:**
- [ ] User connected to hub receives their targeted events
- [ ] Redis backplane means events work across multiple Notification Service replicas

---

### T049 — Kafka Consumers

**Service:** `notification-service`  
**Language:** .NET 9  
**Depends on:** T048, T009  

**Spec:**
Kafka consumers:
- `connection.accepted` → notify requester via SignalR + FCM (`RequestAccepted`)
- `connection.expired` → notify requester via SignalR + FCM (`RequestExpired`)

Connection Service sends `connection.accepted` when recipient accepts — this service fans it out.

**Acceptance criteria:**
- [ ] Event consumed → SignalR notification delivered to connected client within 1 s
- [ ] FCM push sent if user not connected to hub

---

### T050 — Notification Service Docker Image

Same pattern as T014. Depends on T049.

---

## Phase 9 — Report Service (Java)

### T051 — Spring Boot + Spring Data MongoDB Setup

**Service:** `report-service`  
**Language:** Java 21  
**Depends on:** T001, T007  

**Spec:**
Dependencies: Spring Web, Spring Data MongoDB, Spring Kafka, Validation, Actuator, Lombok, MapStruct.

Configure MongoDB via `process.env.MONGO_CONNECTION_STRING`. `ReportRepository extends MongoRepository<Report, String>`.

This service is the only Java service using MongoDB — demonstrates Spring Data MongoDB alongside the JPA services.

**Acceptance criteria:**
- [ ] Service starts connected to MongoDB
- [ ] `ReportRepository.findByReportedId()` query works

---

### T052 — Report Submission

**Service:** `report-service`  
**Language:** Java  
**Depends on:** T051  

**Spec:**
`POST /reports`  
Body: `{ "reported_user_id", "reason", "context" }`  
Reads reporter from `X-User-Id`.

Persist to MongoDB `reports` collection. Count how many reports the target has received (all time). Publish `report.filed` Kafka event including `report_count_for_target`.

**Acceptance criteria:**
- [ ] Report persisted to MongoDB
- [ ] `report.filed` event published
- [ ] `report_count_for_target` in event is accurate

---

### T053 — Escalation Threshold Logic

**Service:** `report-service`  
**Language:** Java  
**Depends on:** T052  

**Spec:**
On each new report, if `report_count_for_target` ≥ escalation threshold (configurable, default 3): mark the user's reports as `escalated` in MongoDB, include `escalated: true` in the Kafka event.

`GET /reports/queue` — admin endpoint (requires `X-User-Role: admin`), returns all `escalated` reports sorted by target's report count descending.

**Acceptance criteria:**
- [ ] 3rd report against same user → `escalated: true` in Kafka event
- [ ] `GET /reports/queue` returns only escalated reports
- [ ] Non-admin calling queue endpoint → 403

---

### T054 — Kafka Producer: report.filed

Already covered in T052. This task adds retry + dead letter handling.

**Spec:**
Wrap `KafkaTemplate.send()` with a retry policy (3 attempts, exponential backoff). Failed events after retries → logged with full payload for manual replay.

**Acceptance criteria:**
- [ ] Transient Kafka failure → retried up to 3 times
- [ ] Permanent failure → logged with payload, HTTP response still 201 (report saved)

---

### T055 — Report Service Docker Image

Same pattern as T028. Depends on T054.

---

## Phase 10 — Admin Service (Java)

### T056 — Spring Boot + Spring Security Setup

**Service:** `admin-service`  
**Language:** Java 21  
**Depends on:** T001  

**Spec:**
Dependencies: Spring Web, Spring Security, JPA (read-only access to `users_db`, `ratings_db`), Actuator, Lombok.

Spring Security config: all endpoints require `X-User-Role: admin` header. Implement as a `OncePerRequestFilter` that reads the header (set by gateway) and populates `SecurityContext`. No JWT re-validation.

**Acceptance criteria:**
- [ ] Request without `X-User-Role: admin` → 403
- [ ] `/actuator/health` is public (excluded from security filter)

---

### T057 — Review Queue Endpoints

**Service:** `admin-service`  
**Language:** Java  
**Depends on:** T056  

**Spec:**
- `GET /admin/queue` — lists accounts in manual review (status = `suspended` in User Service, pulled via HTTP to User Service)
- `GET /admin/users/:id` — full user view: profile, trust score (from Rating Service), report history (from Report Service)
- `POST /admin/users/:id/reinstate` — sets user status back to `active`, publishes `trust.score.updated` with reinstated limits

All inter-service calls use WebClient with Resilience4j.

**Acceptance criteria:**
- [ ] Suspended user appears in queue
- [ ] Reinstate → user active within 1 poll cycle
- [ ] User detail page aggregates data from 3 services correctly

---

### T058 — Trust Score Override

**Service:** `admin-service`  
**Language:** Java  
**Depends on:** T057  

**Spec:**
`PATCH /admin/users/:id/trust-score`  
Body: `{ "score", "reason" }`

Calls Rating Service `PATCH /trust/:userId` with override. Logs the override action with admin's `X-User-Id` and reason.

**Acceptance criteria:**
- [ ] Override applied to Rating Service
- [ ] Override audit log entry created (in-memory log for now, file-backed in prod)

---

### T059 — Account Suspend

**Service:** `admin-service`  
**Language:** Java  
**Depends on:** T057  

**Spec:**
`POST /admin/users/:id/suspend`  
Body: `{ "reason" }`

Calls User Service to set status = `suspended`. Publishes `trust.score.updated` with `request_limit: 0` to remove from discovery.

**Acceptance criteria:**
- [ ] Suspended user removed from Discovery's `scan:eligible_users` within 1 Kafka poll
- [ ] User's active connection requests expired immediately (via Connection Service HTTP call)

---

### T060 — Dashboard Queries

**Service:** `admin-service`  
**Language:** Java  
**Depends on:** T056  

**Spec:**
`GET /admin/stats` — returns:
- Total active users
- Total scans today
- Total connections today (accepted)
- Total reports today
- Top 5 most-reported users (last 7 days)

Data sourced via HTTP calls to respective services. Cache response 60 s in Caffeine.

**Acceptance criteria:**
- [ ] All 5 metrics returned
- [ ] Response cached, subsequent calls within 60 s don't hit downstream services

---

### T061 — Admin Service Docker Image

Same pattern as T028. Depends on T060.

---

## Phase 11 — Angular Frontend

### T062 — Angular PWA Setup

**Service:** `frontend`  
**Language:** TypeScript / Angular 17  
**Depends on:** T001  
**Skills/Commands:** `ng serve`, `ng build`

**Spec:**
`ng add @angular/pwa` — adds service worker, `manifest.webmanifest`, icons.

Configure `ngsw-config.json`:
- Cache strategy `freshness` for API calls (network-first)
- Cache strategy `performance` for assets (cache-first)
- Static routes (`/profile`, `/settings`) pre-cached

`environments/environment.ts` — all API URLs via `process.env.*` (Angular env replacement).

**Acceptance criteria:**
- [ ] `ng build --configuration production` succeeds
- [ ] Lighthouse PWA score ≥ 90
- [ ] App installable on Android Chrome

---

### T063 — Auth Flow (Google Sign-In + OTP)

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T062, T016, T017  

**Spec:**
Two screens: `LoginComponent`, `OtpVerifyComponent`.

- `LoginComponent`: renders Google Sign-In button (`@angular/google-signin-ewf-fortlet` or direct GSI script). On success → call `POST /auth/google`, store JWT in `localStorage`.
- `OtpVerifyComponent`: phone input + 6-digit OTP input. Calls `POST /auth/otp/send` and `POST /auth/otp/verify`.
- `AuthGuard` — redirects unauthenticated users to login.
- `AuthInterceptor` — adds `Authorization: Bearer <jwt>` to all API requests.

**Acceptance criteria:**
- [ ] Full Google → OTP flow completes and JWT is stored
- [ ] Protected routes redirect to login when unauthenticated
- [ ] Expired JWT → refresh attempted, then logout

---

### T064 — Radar UI Component

**Service:** `frontend`  
**Language:** TypeScript / CSS  
**Depends on:** T062  

**Spec:**
`RadarComponent` — animated SVG radar:
- Current user at center
- Rotating sweep animation (CSS `@keyframes`)
- Discovered users appear as animated dots, positioned by approximate bearing and distance
- Tap a dot → opens `UserCardComponent`

`UserCardComponent` — shows: name, photo, rating stars, destination, "leaving in X min", route match %, preferred language, Send Request button.

Mode indicator badge: "GPS" (green) or "BLE" (orange, with range note).

**Acceptance criteria:**
- [ ] Radar sweep animation runs continuously while scan is active
- [ ] User card renders all required fields
- [ ] Mode badge switches correctly

---

### T065 — BLE Mode (Web Bluetooth API)

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T064, T024  

**Spec:**
`BleDiscoveryService`:
1. Detect when device goes offline (`fromEvent(window, 'offline')`).
2. On offline: call `POST /scan/ble/token` (while still resolving from cache), then start BLE advertising (not supported via Web Bluetooth — note: Web Bluetooth supports scanning only, not advertising). Re-architect: use BLE scanning to detect nearby devices that are advertising a known service UUID via a native companion (or flag as future mobile app feature).

**Revised scope for PWA:**  
Web Bluetooth API supports scanning but not advertising from a browser. Update: BLE offline mode in the PWA shows a "Nearby offline users (BLE scan)" UI that initiates a Bluetooth scan for devices advertising the app's service UUID. This requires the companion device to be running a native app or a specific BLE peripheral. For MVP, show the UI and scan, but if no devices found show "No BLE companions found nearby. Enable data to use GPS discovery."

On reconnect: call `POST /scan/ble/resolve` with any detected tokens.

iOS: detect via `navigator.bluetooth === undefined`, show "Bluetooth discovery not supported on iOS. Enable data to continue."

**Acceptance criteria:**
- [ ] Goes offline → BLE mode UI activates
- [ ] `navigator.bluetooth` unavailable → iOS message shown
- [ ] Returns online → GPS mode resumes, resolve called with detected tokens

---

### T066 — Discovery Filters Panel

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T064  

**Spec:**
Slide-up filter panel with:
- Radius slider (0.5 km – 5 km)
- Departure time window (±15 / ±30 / ±60 min)
- Women-only toggle (only shown if user gender = female)

Filters stored in `ScanSessionService`, sent with `POST /scan/start` body.

**Acceptance criteria:**
- [ ] Women-only toggle hidden for non-female users
- [ ] Filter changes apply on next scan start
- [ ] Default values match REQUIREMENTS.md (1 km, ±30 min)

---

### T067 — Connection Request Flow

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T064, T030, T048  

**Spec:**
- Send button on `UserCardComponent` → calls `POST /connections`, shows "Request sent" state.
- `NotificationService` (Angular) subscribes to SignalR hub. On `ConnectionRequest` event → shows in-app banner + sound.
- `InboundRequestComponent` — Accept / Decline buttons, shows sender's card.
- On `RequestAccepted` event → navigate to chat.
- On `RequestDeclined` / `RequestExpired` → show toast.

**Acceptance criteria:**
- [ ] Full send → accept → chat navigation works
- [ ] Decline and expiry show correct feedback
- [ ] SignalR reconnects automatically on connection drop

---

### T068 — Chat UI

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T067, T036  

**Spec:**
`ChatComponent`:
- Message list (reverse scroll, newest at bottom)
- Text input + send button
- "Met Successfully" button (prominent, above input)
- Shows both users' names in header
- Offline banner when disconnected from SignalR

On `ChatOpened` SignalR event → navigate here.  
On `ChatClosed` event → navigate to rating screen.

**Acceptance criteria:**
- [ ] Messages appear in real-time without refresh
- [ ] Met Successfully → confirmation dialog → rating screen
- [ ] Offline state shows banner, input disabled

---

### T069 — Rating Flow

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T068, T042  

**Spec:**
`RatingComponent` — shown after chat closes.
- Shows the other user's name and photo
- Tag grid (positive tags green, negative tags red)
- Multi-select (can pick multiple tags)
- Submit button → calls `POST /ratings`
- On timeout path: "Skip" button available

**Acceptance criteria:**
- [ ] At least one tag required on Met Successfully path (submit disabled otherwise)
- [ ] Skip available on timeout path
- [ ] After submit → navigate to home/radar

---

### T070 — Profile + Settings

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T063, T018  

**Spec:**
`ProfileComponent` — edit name, photo (upload), preferred language, gender (one-time set, cannot change after first scan).  
`SettingsComponent` — status toggle (Looking / Not available), women-only mode toggle (conditional on gender), account info.

**Acceptance criteria:**
- [ ] Gender field locked after first scan session
- [ ] Women-only toggle only visible for female users
- [ ] Profile photo upload works (multipart POST to User Service)

---

### T071 — Admin Dashboard UI

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T057, T058, T059, T060  

**Spec:**
`AdminModule` — lazy loaded, guarded by `AdminGuard` (checks `X-User-Role` claim in JWT).

Pages:
- Queue list (flagged accounts)
- User detail (profile + trust score + reports)
- Stats dashboard (T060 data)

**Acceptance criteria:**
- [ ] Non-admin JWT → cannot access admin routes
- [ ] Suspend and reinstate actions update UI immediately
- [ ] Stats dashboard refreshes every 60 s

---

### T072 — Offline State Handling

**Service:** `frontend`  
**Language:** TypeScript  
**Depends on:** T065  

**Spec:**
`OfflineService` — monitors `online`/`offline` events. When offline:
- Static cached screens work
- Discovery switches to BLE (T065)
- Chat shows offline banner, disables input
- All API error responses from service worker fallback → show user-friendly message

**Acceptance criteria:**
- [ ] Profile screen loads from cache when offline
- [ ] Chat input disabled when SignalR disconnected
- [ ] API failure → toast "You appear to be offline" instead of raw error

---

## Phase 12 — Testing

### T081 — Unit Tests: Java Services

**Service:** All Java services  
**Language:** Java / JUnit 5 + Mockito  
**Depends on:** T028, T034, T046, T055, T061  

**Spec:**
Per service, minimum test coverage:
- All `@Service` methods with mocked repositories
- `TrustScoreCalculator` fully unit tested (T043 has specific cases)
- Kafka producer: verify `KafkaTemplate.send()` called with correct topic + payload
- Controller layer: `@WebMvcTest` for each endpoint (happy path + validation errors)

Target: 80% line coverage on service + domain layers.

**Acceptance criteria:**
- [ ] `mvn test` passes for all Java services
- [ ] Coverage report generated (Jacoco)
- [ ] `TrustScoreCalculator` has ≥ 10 test cases

---

### T082 — Unit Tests: .NET Services

**Service:** All .NET services  
**Language:** C# / xUnit + Moq  
**Depends on:** T020, T040, T050  

**Spec:**
Per service:
- All service-layer methods with mocked dependencies
- Controller tests using `WebApplicationFactory<Program>`
- Kafka producer tests: mock `IProducer`, verify publish calls

**Acceptance criteria:**
- [ ] `dotnet test` passes for all .NET services
- [ ] Coverage ≥ 80% on service layer (Coverlet)

---

### T083 — Integration Tests: Java (Testcontainers)

**Service:** Discovery, Connection, Rating  
**Language:** Java / Testcontainers  
**Depends on:** T081  

**Spec:**
Use Testcontainers to spin up real SQL Server + Kafka containers per test class. Test the full stack: HTTP request → JPA → Kafka → consumer → DB assertion.

Minimum: one integration test per Kafka producer/consumer pair.

**Acceptance criteria:**
- [ ] Tests spin up containers automatically
- [ ] Kafka round-trip test: produce event → consumer processes → DB state verified
- [ ] Tests are independent (no shared state between test classes)

---

### T084 — Integration Tests: .NET (Testcontainers)

**Service:** User Service, Chat Service  
**Language:** C# / Testcontainers.MsSql + Testcontainers.MongoDb  
**Depends on:** T082  

Same pattern as T083 for .NET services.

---

### T085 — E2E Tests (Playwright)

**Service:** `frontend` + all services  
**Language:** TypeScript / Playwright  
**Depends on:** T072, all backend services  

**Spec:**
Three E2E scenarios:
1. **Happy path:** User A and User B both sign in → scan → A sends request → B accepts → chat opens → both tap Met → rating submitted.
2. **Expiry path:** Request expires after TTL → both users see expiry notification.
3. **Report flow:** User reports another → report appears in admin queue.

Run against `docker compose` environment.

**Acceptance criteria:**
- [ ] All 3 scenarios pass
- [ ] Tests run headless in CI (`playwright test --reporter=list`)

---

### T086 — Load Test: Discovery Query

**Service:** `discovery-service`  
**Language:** k6  
**Depends on:** T083  

**Spec:**
k6 script: 50 virtual users, each running `GET /scan/nearby` for 60 s.  
Assert P95 latency ≤ 500 ms (REQUIREMENTS.md NFR).

**Acceptance criteria:**
- [ ] P95 ≤ 500 ms at 50 VU
- [ ] No errors above 1%

---

## Phase 13 — Observability

### T087 — OpenTelemetry: Java Services

**Service:** All Java services  
**Language:** Java  
**Depends on:** T028, T034, T046, T055, T061  

**Spec:**
Add `opentelemetry-spring-boot-starter` to all Java services. Configure OTLP exporter to `process.env.OTEL_EXPORTER_OTLP_ENDPOINT`. Auto-instrumentation covers HTTP + JDBC + Kafka.

Log format: JSON (Logback with `logstash-logback-encoder`). Every log line includes `traceId`, `spanId`, `service.name`.

**Acceptance criteria:**
- [ ] Trace IDs appear in logs
- [ ] HTTP spans visible in OTLP-compatible backend (e.g., Jaeger in Docker Compose)
- [ ] Kafka consumer/producer spans linked to HTTP parent

---

### T088 — OpenTelemetry: .NET Services

**Service:** All .NET services  
**Language:** C#  
**Depends on:** T020, T040, T050  

**Spec:**
Add `OpenTelemetry.Extensions.Hosting`, `OpenTelemetry.Instrumentation.AspNetCore`, `OpenTelemetry.Instrumentation.Http`, `OpenTelemetry.Exporter.OpenTelemetryProtocol`.

Log format: Serilog JSON sink. Every log includes `TraceId`, `SpanId`.

**Acceptance criteria:**
- [ ] Same traceId propagates from Gateway → .NET service → Kafka → Java service
- [ ] Logs structured as JSON

---

### T089 — Jaeger in Docker Compose

**Service:** `/infra`  
**Language:** YAML  
**Depends on:** T004  

**Spec:**
Add Jaeger all-in-one to `docker-compose.yml`. OTLP endpoint on port 4317. Jaeger UI on port 16686.

**Acceptance criteria:**
- [ ] `http://localhost:16686` shows Jaeger UI
- [ ] Traces from T087/T088 visible after running a request

---

### T090 — Health Checks Consolidation

**Service:** All services  
**Language:** Mixed  
**Depends on:** all service Docker images  

**Spec:**
Verify every service's Docker healthcheck passes in `docker compose ps`. Add a `/infra/healthcheck.sh` script that polls all 9 service health endpoints and exits 0 only when all are UP.

**Acceptance criteria:**
- [ ] `docker compose ps` shows all services as `healthy`
- [ ] `./infra/healthcheck.sh` exits 0 when stack is fully up

---

## Quick Reference

| ID | Title | Phase | Lang | Depends |
|---|---|---|---|---|
| T001 | Monorepo Scaffold | 0 | — | — |
| T002 | Kafka Topic Contracts | 0 | JSON | T001 |
| T003 | Shared Error Envelope | 0 | C#/Java | T001 |
| T004 | Docker Compose Infra | 0 | YAML | T001 |
| T005 | OpenAPI Specs | 0 | YAML | T002,T003 |
| T006 | SQL Server Schemas | 1 | SQL | T004 |
| T007 | MongoDB Setup | 1 | YAML | T004 |
| T008 | Redis Setup | 1 | YAML | T004 |
| T009 | Kafka Topics Init | 1 | Shell | T004 |
| T010 | Scoop + JDK Install Script | 1 | PS1 | — |
| T011 | YARP Gateway Setup | 2 | .NET | T001,T003 |
| T012 | JWT Validation Middleware | 2 | .NET | T011 |
| T013 | Rate Limiting | 2 | .NET | T012 |
| T014 | Gateway Docker | 2 | Docker | T013 |
| T015 | User Service Setup | 3 | .NET | T001,T003,T006 |
| T016 | Google OAuth + JWT | 3 | .NET | T015 |
| T017 | Phone OTP | 3 | .NET | T016 |
| T018 | Profile CRUD | 3 | .NET | T016 |
| T019 | Identity Verification | 3 | .NET | T018 |
| T020 | Kafka: user.verified | 3 | .NET | T017,T018,T009 |
| T021 | Discovery Setup | 4 | Java | T001,T008,T009 |
| T022 | Geospatial + Scan Session | 4 | Java | T021,T006 |
| T023 | GPS Discovery Query | 4 | Java | T022 |
| T024 | BLE Token | 4 | Java | T022 |
| T025 | Kafka Consumers (Discovery) | 4 | Java | T021,T009 |
| T026 | Redis Cache Layer | 4 | Java | T021 |
| T027 | Resilience4j Circuit Breaker | 4 | Java | T026 |
| T028 | Discovery Docker | 4 | Docker | T027 |
| T029 | Connection Service Setup | 5 | Java | T001,T006 |
| T030 | Request Lifecycle Endpoints | 5 | Java | T029 |
| T031 | TTL Expiry Job | 5 | Java | T030 |
| T032 | Kafka Producer (Connection) | 5 | Java | T030,T031,T009 |
| T033 | Kafka Consumer (Trust) | 5 | Java | T032 |
| T034 | Connection Docker | 5 | Docker | T033 |
| T035 | Chat Service Setup | 6 | .NET | T001,T007 |
| T036 | SignalR Hub | 6 | .NET | T035 |
| T037 | Chat Lifecycle | 6 | .NET | T036 |
| T038 | Kafka Consumer (connection.accepted) | 6 | .NET | T037,T009 |
| T039 | Kafka Producer (chat.closed) | 6 | .NET | T037 |
| T040 | Chat Docker | 6 | Docker | T039 |
| T041 | Rating Service Setup | 7 | Java | T001,T006 |
| T042 | Rating Submission | 7 | Java | T041 |
| T043 | Trust Score Algorithm | 7 | Java | T042 |
| T044 | Kafka Consumer+Producer (Rating) | 7 | Java | T043,T009 |
| T045 | Trust Score Endpoints | 7 | Java | T044 |
| T046 | Rating Docker | 7 | Docker | T045 |
| T047 | FCM Setup | 8 | .NET | T001 |
| T048 | SignalR Notification Hub | 8 | .NET | T047 |
| T049 | Kafka Consumers (Notification) | 8 | .NET | T048,T009 |
| T050 | Notification Docker | 8 | Docker | T049 |
| T051 | Report Service Setup (MongoDB) | 9 | Java | T001,T007 |
| T052 | Report Submission | 9 | Java | T051 |
| T053 | Escalation Threshold | 9 | Java | T052 |
| T054 | Kafka Producer + Retry (Report) | 9 | Java | T052 |
| T055 | Report Docker | 9 | Docker | T054 |
| T056 | Admin Service + Spring Security | 10 | Java | T001 |
| T057 | Review Queue Endpoints | 10 | Java | T056 |
| T058 | Trust Score Override | 10 | Java | T057 |
| T059 | Account Suspend | 10 | Java | T057 |
| T060 | Dashboard Queries | 10 | Java | T056 |
| T061 | Admin Docker | 10 | Docker | T060 |
| T062 | Angular PWA Setup | 11 | TS | T001 |
| T063 | Auth Flow | 11 | TS | T062,T016,T017 |
| T064 | Radar UI | 11 | TS | T062 |
| T065 | BLE Mode | 11 | TS | T064,T024 |
| T066 | Discovery Filters Panel | 11 | TS | T064 |
| T067 | Connection Request Flow | 11 | TS | T064,T030,T048 |
| T068 | Chat UI | 11 | TS | T067,T036 |
| T069 | Rating Flow | 11 | TS | T068,T042 |
| T070 | Profile + Settings | 11 | TS | T063,T018 |
| T071 | Admin Dashboard UI | 11 | TS | T057,T058,T059,T060 |
| T072 | Offline State Handling | 11 | TS | T065 |
| T081 | Unit Tests: Java | 12 | Java | T028,T034,T046,T055,T061 |
| T082 | Unit Tests: .NET | 12 | C# | T020,T040,T050 |
| T083 | Integration Tests: Java | 12 | Java | T081 |
| T084 | Integration Tests: .NET | 12 | C# | T082 |
| T085 | E2E Tests (Playwright) | 12 | TS | T072 |
| T086 | Load Test: Discovery | 12 | k6 | T083 |
| T087 | OTel: Java | 13 | Java | T028+ |
| T088 | OTel: .NET | 13 | C# | T020+ |
| T089 | Jaeger in Docker Compose | 13 | YAML | T004 |
| T090 | Health Checks Consolidation | 13 | Shell | all |
| T091 | Outbox: shared-java-lib module | 14 | Java | T003 |
| T092 | Outbox: .NET shared middleware | 14 | .NET | T003 |
| T093 | Outbox: apply to all Java services | 14 | Java | T091 |
| T094 | Outbox: apply to all .NET services | 14 | .NET | T092 |
| T095 | Idempotency: consumer dedup (Java) | 14 | Java | T093 |
| T096 | Saga state: ConnectionLifecycleSaga | 14 | Java+.NET | T094,T095 |

---

## Phase 14 — Saga + Outbox

### T091 — Outbox Module: shared-java-lib

**Service:** `shared-java-lib`  
**Language:** Java  
**Depends on:** T003  
**Skills/Commands:** `mvn install`

**Spec:**
Add to the shared Maven library (T003):

1. `OutboxEvent` JPA entity:
   ```java
   @Entity @Table(name = "outbox")
   class OutboxEvent {
     UUID id;              // event_id, used by consumers for dedup
     String topic;
     String partitionKey;
     String payload;       // JSON
     String status;        // 'pending' | 'processed' | 'failed'
     Instant createdAt;
     Instant processedAt;
   }
   ```

2. `OutboxRepository extends JpaRepository<OutboxEvent, UUID>`

3. `OutboxRelay` — `@Component` with `@Scheduled(fixedDelay = 500)`:
   - Fetches top 50 `status='pending'` records ordered by `created_at`
   - Publishes each to Kafka via `KafkaTemplate`
   - On success: marks `status='processed'`, sets `processedAt`
   - On failure after 3 retries: marks `status='failed'`, logs full payload

4. `OutboxService.publish(topic, partitionKey, payload)` — used by services to write to outbox in the same transaction as the business record.

All Java services that produce Kafka events import this module and call `OutboxService.publish()` instead of calling `KafkaTemplate` directly.

**Acceptance criteria:**
- [ ] `OutboxRelay` publishes pending events within 500 ms of insert
- [ ] If Kafka is down: events stay as `pending`, no data loss
- [ ] `OutboxRelay` is idempotent — re-running never double-publishes a `processed` event
- [ ] Module installs with `mvn install -pl shared-java-lib`

---

### T092 — Outbox Middleware: .NET Services

**Service:** `shared .NET lib`  
**Language:** .NET 9  
**Depends on:** T003  
**Skills/Commands:** none

**Spec:**
Create a shared class library project `ShareConnectSave.Outbox` (NuGet-style, referenced by all .NET services).

1. `OutboxMessage` EF Core entity (same shape as T091 Java entity).
2. `OutboxRelay : BackgroundService` — polls every 500 ms, publishes via `IProducer<string, string>`, marks processed.
3. `IOutboxService.PublishAsync(topic, partitionKey, payload)` — writes to outbox table in caller's `DbContext` transaction.

Registration: `services.AddOutbox<TDbContext>()` extension method.

**Acceptance criteria:**
- [ ] Same guarantees as T091 — no double publish, no lost events
- [ ] Works with the existing `UsersDbContext` and `ChatDbContext` transaction scopes

---

### T093 — Apply Outbox to All Java Services

**Service:** Connection, Rating, Report services  
**Language:** Java  
**Depends on:** T091, T032, T044, T054  

**Spec:**
Replace all direct `KafkaTemplate.send()` calls in Java services with `OutboxService.publish()`.

For each service:
1. Add `outbox` table Flyway migration (same schema as T091 entity).
2. Inject `OutboxService` where `KafkaTemplate` was used.
3. Wrap business record write + outbox write in a single `@Transactional` block.
4. Remove direct `KafkaTemplate` injection from business services (only `OutboxRelay` uses it now).

Services affected: Connection (T032), Rating (T044), Report (T054).

**Acceptance criteria:**
- [ ] Kill Kafka mid-request → business record saved, outbox record saved, event published when Kafka recovers
- [ ] No direct `KafkaTemplate` calls remain outside of `OutboxRelay`
- [ ] Each service's `outbox` table visible in SQL Server

---

### T094 — Apply Outbox to All .NET Services

**Service:** User Service, Chat Service  
**Language:** .NET 9  
**Depends on:** T092, T020, T039  

Same pattern as T093 for .NET services (User: T020, Chat: T039).

**Acceptance criteria:**
- [ ] Same transactional guarantees as T093
- [ ] `outbox` table present in `users_db` and `chat_db`

---

### T095 — Consumer Idempotency (Java)

**Service:** All Java Kafka consumers  
**Language:** Java  
**Depends on:** T093  

**Spec:**
Add to `shared-java-lib`:

1. `ProcessedEvent` JPA entity: `(event_id UUID PK, processed_at INSTANT)`.
2. `IdempotencyFilter` — called at the start of every `@KafkaListener` method:
   - Check if `event_id` exists in `processed_events` table.
   - If yes: log `"Duplicate event {event_id}, skipping"` and return early.
   - If no: process, then insert `processed_events` record.
3. Pruning job: delete `processed_events` records older than 7 days (runs daily).

All Java `@KafkaListener` methods must call `idempotencyFilter.check(eventId)` as their first line.

**Acceptance criteria:**
- [ ] Publishing the same event twice → processed exactly once
- [ ] Duplicate log line appears on second delivery
- [ ] `processed_events` pruned after 7 days

---

### T096 — ConnectionLifecycleSaga State Tracking

**Service:** Connection Service (Java), Chat Service (.NET)  
**Language:** Java + .NET  
**Depends on:** T094, T095  

**Spec:**
Implement saga state tracking for the `ConnectionLifecycleSaga` across both services.

**Connection Service (Java):**
- Add `saga_state` table (see REQUIREMENTS.md schema).
- On `ACCEPTED` transition: insert saga record `{ saga_id: connection_id, saga_type: 'ConnectionLifecycle', current_step: 'CHAT_OPENING', status: 'in_progress' }`.
- Consume `chat.closed` event: update saga to `{ current_step: 'COMPLETED', status: 'completed' }`.
- If chat fails to open within 5 minutes (no `ChatOpened` confirmation): compensating transaction → revert connection status to `PENDING`, update saga `status: 'compensating'`, publish `connection.chat-failed` event (new topic — add to T002 contracts).

**Chat Service (.NET):**
- Add `saga_state` EF Core entity.
- On `connection.accepted` Kafka event: insert saga record `{ current_step: 'CHAT_OPEN', status: 'in_progress' }`.
- On chat close (any path): update saga to `completed`.
- If chat room creation fails: publish `connection.chat-failed` event.

**Acceptance criteria:**
- [ ] Saga record created when connection accepted
- [ ] Happy path: saga reaches `completed` after chat closes
- [ ] Compensating path: kill Chat Service after accept → Connection Service detects timeout → reverts to PENDING within 5 min → saga shows `compensating`
- [ ] `connection.chat-failed` topic added to Kafka init script (T009)
