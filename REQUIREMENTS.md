# ShareConnectSave — Full Requirements

## Stack Decisions

| Layer | Choice | Rationale |
|---|---|---|
| Backend | .NET 9 Minimal APIs (microservices) | Team familiarity, SignalR is first-class, high perf |
| Frontend | Angular (PWA) — **module-based, no standalone components** | Structured, service-worker + Web Bluetooth API support |
| Native App (Phase 2) | Capacitor.js (wraps same Angular codebase) | Web-first, native app via Capacitor — no rewrite |
| API Gateway | YARP (Yet Another Reverse Proxy) | Native .NET, JWT validation + routing |
| Realtime | SignalR | Native .NET, handles connection request + chat events |
| Auth | Google Sign-In + JWT | Fast onboarding, no password management |
| Relational DB | SQL Server | Users, requests, ratings, blocks, reports (per-service schemas) |
| Document DB | MongoDB | Temporary chat messages (TTL index, auto-expire) |
| Messaging | Kafka | Async cross-service events (discovery, ratings, notifications) |
| Maps | Google Maps Platform | Geocoding, route overlap calculation |
| Push | Firebase Cloud Messaging | Background request/acceptance notifications |
| Orchestration | Docker Compose (dev) / Kubernetes (prod) | Service isolation and scaling |
| Saga style | Choreography (Kafka events) + Outbox Pattern | No extra orchestrator service; atomic DB+event writes |

---

## Architecture — Microservices

Each service is an independent .NET 9 Minimal API with its own database schema. Services communicate:
- **Async** via Kafka for cross-domain events (e.g., rating submitted → trust score recalculated)
- **Sync** via HTTP through the API Gateway for client-facing request/response

```
Angular PWA
    │
    ▼
API Gateway (YARP)          ← JWT validation, rate limiting, routing
    │
    ├── User Service         ← Auth, profiles, OTP, identity verification
    ├── Discovery Service    ← Geospatial scan, BLE coordination, filters
    ├── Connection Service   ← Request lifecycle (pending → accepted → expired)
    ├── Chat Service         ← Messages (MongoDB), SignalR hub, TTL expiry
    ├── Rating Service       ← Submission, trust score calculation
    ├── Notification Service ← FCM push, SignalR event fan-out
    ├── Report Service       ← Report queue, admin escalation
    └── Admin Service        ← Dashboard, review queue, account actions

Kafka topics:
  user.verified → Discovery Service (enable in scan pool)
  connection.accepted → Chat Service (open chat room)
  chat.closed → Rating Service (trigger rating prompt)
  rating.submitted → Rating Service (recalculate trust score)
  trust.score.updated → Discovery Service (update visibility/limits)
  report.filed → Report Service (escalate if threshold hit)
```

---

## Functional Requirements

### 1. Authentication & Onboarding

- Sign in via Google OAuth 2.0.
- After Google sign-in, mandatory phone number verification (OTP).
- Profile setup: name (from Google), profile photo, preferred language, gender.
- **Identity verification badge**: optional step after profile setup.
  - Flow: user submits a selfie → system does liveness + face-match against their profile photo.
  - **If Google account has no profile photo** (common with work/school accounts): user is prompted to upload a photo during profile setup. That uploaded photo becomes the base for face matching. Cannot start identity verification without a base photo.
  - Verified accounts display a badge.
- JWT issued after full verification; refresh token stored securely.

### 2. User Profile

- Fields: name, photo, gender, preferred language, rating (avg), trust badge, verification badge.
- Users can set their status: **Looking for companion** (visible in discovery) or **Not available** (hidden).
- Trust score is derived from ratings (not directly user-editable).

### 3. Discovery / Radar — GPS Mode (Online)

- User enters destination and departure time before starting a scan.
- Tapping **Start Scan** activates the radar UI.
- Location is tracked continuously while scan is active (stops on exit or scan close).
- Backend queries nearby users in real time using geospatial index.
- Discovery filters applied server-side:
  - Within configurable radius (default 1 km)
  - Similar route (route overlap % above threshold)
  - Departure time within ±30 min window
  - Not on user's block list (bidirectional)
  - Status = Looking for companion
- **Women-only mode**: when enabled by a woman, radar exclusively shows other women.
- Each discovered user shown as a card: name, photo, rating, destination, "leaving in X min", route match %, preferred language.

### 4. Discovery — Bluetooth Mode (Offline Fallback)

Activates automatically when the device loses internet connectivity during an active scan.

**How it works:**
- The Angular PWA uses the **Web Bluetooth API** to advertise and scan for BLE devices.
- Each device running the app broadcasts a BLE advertisement containing:
  - A fixed app Service UUID (identifies the app)
  - A short-lived rotating token (hashed, not a raw user ID) that refreshes every 5 minutes
- When another device running the app is detected, it appears on the radar locally with a BLE indicator.
- Profile details (name, photo, destination) cannot be resolved offline — the radar shows "Nearby user (offline)" with signal strength as approximate distance.
- When connectivity is restored, the app exchanges the rotating tokens with the server to resolve full profiles and enable a connection request.

**Platform constraints (known):**
- Android Chrome: full Web Bluetooth API support ✓
- iOS Safari: Web Bluetooth blocked by Apple — BLE mode unavailable on iOS PWA ✗
- iOS users see a clear "Bluetooth discovery not available on iOS" message with a prompt to enable Wi-Fi/data.

**BLE range:** 10–30 m. Discovery pool is much smaller than GPS mode. This is expected and communicated to the user via UI copy ("Bluetooth range — very nearby only").

**Privacy:** Rotating tokens are never linked to user identity on the client. Server-side resolution happens only when back online and the user explicitly attempts a connection.

### 5. Connection Requests

- User taps a profile and sends a request.
- Request states: `pending` → `accepted` / `declined` / `expired`.
- Recipient receives a real-time SignalR notification + FCM push (for background).
- Requests expire automatically after a configurable TTL (e.g., 10 minutes with no response).
- On acceptance: temporary chat opens for both parties.

### 6. Temporary Chat

- Opens only after mutual acceptance of a connection request.
- Backed by MongoDB with a TTL index — messages auto-deleted on expiry.
- Chat lifecycle:
  1. Opens on acceptance.
  2. Either user taps **Met Successfully** → rating flow triggers, chat closes after 5-min grace period.
  3. If neither taps Met Successfully within 2 hours, chat auto-closes.
- No message history persists after chat closes — hard requirement.
- Real-time delivery via SignalR.

### 7. Ratings

Triggered after chat closes.

**Positive tags:** Friendly, Respectful, On time, Good communication, Would travel again.
**Negative tags:** Didn't show up, Fake destination, Abusive, Spam, Asked for money.

- Ratings are mandatory on the Met Successfully path, optional on timeout path.
- Rating affects trust score → discovery visibility and request-send limits.

**Trust score rules:**
- High average → Trusted badge on profile.
- Repeated negative ratings → request-sending throttled.
- Very low score or multiple abuse reports → account queued for manual review, suspended from discovery.

### 8. Reports

- User can report another from their profile or within chat.
- Reason categories: Harassment, Fake profile, Inappropriate content, Asked for money, Other.
- Reports stored and surfaced in admin queue.
- Multiple reports against same account escalate queue priority (Kafka event threshold).

### 9. Block

- User can block another from their profile or post-chat.
- Blocked users never appear in each other's discovery (bidirectional, server-enforced).
- Block list is permanent until manually removed.

### 10. Women-Only Mode

- Available only to users with gender = Female.
- Toggle in settings or on the radar screen.
- When active: discovery query server-side filters to female users only.
- Gender field is not exposed in API responses to other users — only used as a filter predicate.

### 11. Admin Dashboard

- View and action the manual review queue.
- Override trust scores.
- View per-user report history.
- Suspend or reinstate accounts.

---

## Non-Functional Requirements

### Offline / PWA

- Angular PWA with service worker.
- Static screens (profile, settings) load from cache when offline.
- Discovery switches to BLE mode when offline (see §4).
- Chat requires connectivity — shows clear offline state.
- App is installable on Android home screen.

### Location Privacy

- Coordinates never stored permanently — used only for live discovery query, discarded after scan ends.
- Users see approximate distance to a match, never raw coordinates.

### Chat Privacy

- MongoDB TTL index is the enforcement mechanism for message expiry.
- No export, download, or history endpoint exists — by design, not missing feature.

### Performance

- Discovery query P95 < 500 ms under load.
- SignalR hubs scaled via Redis backplane across Chat and Notification services.

### Security

- JWT validation at API Gateway (not repeated per service).
- Per-user rate limiting on connection requests.
- OTP lockout after N failed attempts.
- BLE rotating tokens must not be reversible to user identity client-side.

---

## Distributed Transactions — Saga + Outbox

### Outbox Pattern (applied to every Kafka-producing service)

Problem: a service can write to its DB and then fail before publishing to Kafka — leaving other services unaware.

Solution: in the same DB transaction, write the event to a local `outbox` table. A background relay (polling every 500 ms) reads the outbox and publishes to Kafka. On successful publish, the outbox record is marked `processed`.

```
Business logic:
  BEGIN TX
    INSERT INTO connection_requests (...)   ← business record
    INSERT INTO outbox (event_type, payload, status='pending')
  COMMIT TX

Outbox relay (background):
  SELECT * FROM outbox WHERE status='pending' ORDER BY created_at LIMIT 50
  → publish each to Kafka
  → UPDATE outbox SET status='processed' WHERE id=...
```

Every service that publishes Kafka events has:
- An `outbox` table (SQL) or `outbox` collection (MongoDB)
- An `OutboxRelay` background service/job
- Events include a unique `event_id` (UUID) for consumer deduplication

### Idempotency (applied to every Kafka consumer)

Kafka delivers at-least-once. Every consumer must deduplicate:
- Maintain a `processed_events` table: `(event_id, processed_at)`
- On receipt: check if `event_id` exists → if yes, skip; if no, process + insert
- Table is pruned after 7 days

### Sagas

#### ConnectionLifecycleSaga

The primary saga — spans Connection, Chat, Rating, and Discovery services.

| Step | Service | Action | Compensating Transaction |
|---|---|---|---|
| 1 | Connection | Request sent (PENDING) | — |
| 2 | Connection | Request accepted (ACCEPTED) | Revert to PENDING if step 3 fails |
| 3 | Chat | Chat room opened | Close chat room + revert connection to PENDING |
| 4 | Chat | Met / timeout | — |
| 5 | Chat | Chat closed | — |
| 6 | Rating | Ratings prompted (both users) | Auto-skip after 24 h if no response |
| 7 | Rating | Ratings submitted | — |
| 8 | Rating + Discovery | Trust score updated, discovery pool refreshed | — |

Each service maintains a `saga_state` record for the saga instance it participates in. Saga ID = `connection_id` (the natural key that flows through all events).

#### UserOnboardingSaga

Append-only — no compensating transactions. Each step is independently retryable by the user.

`Google auth → phone verified → profile complete → user.verified published`

#### ReportEscalationSaga

Append-only. Triggered by `report.filed` events.

`Report filed → count checked → threshold hit → trust recalculated → discovery updated`

### Saga State Table (per participating service)

```sql
CREATE TABLE saga_state (
  saga_id       UNIQUEIDENTIFIER NOT NULL,   -- connection_id
  saga_type     VARCHAR(64) NOT NULL,         -- 'ConnectionLifecycle'
  current_step  VARCHAR(64) NOT NULL,
  status        VARCHAR(32) NOT NULL,         -- 'in_progress' | 'completed' | 'compensating' | 'failed'
  context       NVARCHAR(MAX),                -- JSON: snapshot of relevant IDs
  started_at    DATETIME2 NOT NULL,
  updated_at    DATETIME2 NOT NULL
);
```

---

## Polyglot Integration Contracts

### JWT / Identity Propagation

API Gateway (YARP) validates the JWT once. It injects these headers into every downstream request — both .NET and Java services read these, never re-validate the token:

```
X-User-Id:     <uuid>
X-User-Role:   user | admin
X-User-Gender: female | male | unspecified
```

### Kafka Message Schemas

All topics use JSON. Field naming: `snake_case`. Schemas stored in `/contracts/kafka/`. Both .NET and Java deserialize strictly — unknown fields are ignored, missing required fields throw.

| Topic | Producer | Consumers | Key fields |
|---|---|---|---|
| `user.verified` | User Service | Discovery | `user_id`, `gender`, `verified_at` |
| `connection.accepted` | Connection Service | Chat, Notification | `connection_id`, `requester_id`, `recipient_id` |
| `connection.expired` | Connection Service | Notification | `connection_id`, `requester_id` |
| `chat.closed` | Chat Service | Rating, Notification | `chat_id`, `connection_id`, `user_a_id`, `user_b_id`, `closed_reason` |
| `rating.submitted` | Rating Service | Rating (self) | `rating_id`, `rater_id`, `rated_id`, `tags[]` |
| `trust.score.updated` | Rating Service | Discovery | `user_id`, `new_score`, `badge_level`, `request_limit` |
| `report.filed` | Report Service | Admin | `report_id`, `reporter_id`, `reported_id`, `reason`, `report_count_for_target` |

### HTTP Error Envelope

All services return the same shape regardless of language:

```json
{
  "code": "CONNECTION_REQUEST_EXPIRED",
  "message": "Human-readable description.",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

.NET: `ProblemDetails` middleware. Java: `@ControllerAdvice` with `ErrorResponse` record. Same JSON keys.

### Service URLs (Docker Compose)

Services resolve each other by container name:

| Service | Container name | Port |
|---|---|---|
| API Gateway | `api-gateway` | 8080 |
| User Service | `user-service` | 8081 |
| Discovery Service | `discovery-service` | 8082 |
| Connection Service | `connection-service` | 8083 |
| Chat Service | `chat-service` | 8084 |
| Rating Service | `rating-service` | 8085 |
| Notification Service | `notification-service` | 8086 |
| Report Service | `report-service` | 8087 |
| Admin Service | `admin-service` | 8088 |

### Distributed Tracing

All services instrument with OpenTelemetry. W3C `traceparent` header propagates across all HTTP hops. Trace IDs appear in every log line and every error response.

---

## Java Libraries (Standardised Across All Java Services)

| Purpose | Library |
|---|---|
| Boilerplate reduction | Lombok |
| DTO mapping | MapStruct |
| DB migrations | Flyway |
| HTTP client | WebClient (Spring WebFlux) |
| Circuit breaker | Resilience4j |
| Kafka | Spring Kafka |
| Validation | Spring Boot Validation (`jakarta.validation`) |
| Testing | JUnit 5 + Mockito + Testcontainers |
| Observability | Micrometer + OpenTelemetry |

---

## Out of Scope (MVP)

- Ride booking, cab integration, payments, fare splitting, live ride tracking, driver onboarding.
- AI route recommendations.
- Enterprise/office commute mode.
- Airport / railway station specific flows.
- Event travel.
- iOS Bluetooth discovery (Apple platform restriction).
