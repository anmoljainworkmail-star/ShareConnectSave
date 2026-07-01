# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**ShareConnectSave** is a Travel Companion Discovery Platform. It helps verified travelers nearby find each other when heading in the same direction. Facilitates discovery, matching, and temporary communication only — no ride booking, payments, or tracking.

Responsibility boundary: once both users mark **Met Successfully**, the temporary chat auto-closes and the platform's involvement ends.

## Learning Objective

This project exists primarily to learn. Every implementation decision should be an opportunity to demonstrate a concept. When writing code:
- Add a short comment above non-obvious blocks naming the pattern/principle being applied and why.
- Prefer the implementation that teaches more over the one that is marginally simpler.
- When a design choice maps to a SOLID principle or system design principle, call it out inline.

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Angular (PWA, **module-based — no standalone components**) |
| Native App (later) | Capacitor.js wrapping the same Angular codebase |
| API Gateway | YARP (.NET 9) |
| Backend | .NET 9 Minimal APIs — microservices |
| Realtime | SignalR (Redis backplane for scale) |
| Auth | Google Sign-In + JWT (validated at gateway) |
| Relational DB | SQL Server (per-service schemas) |
| Document DB | MongoDB (chat + reports — TTL expiry) |
| Messaging | Kafka |
| Distributed Tx | Choreography Saga + Outbox Pattern |
| Maps | Google Maps Platform |
| Push | Firebase Cloud Messaging |
| Offline Discovery | Web Bluetooth API (Android only) |
| Dev orchestration | Docker Compose |

## Angular Rules

- All components, services, pipes, and directives must belong to a **NgModule**. No standalone components.
- Feature modules are lazy-loaded via `loadChildren` in the app router.
- Shared UI goes in `SharedModule`. Feature-specific logic stays in its own feature module.

## Microservices

```
Angular PWA → API Gateway (YARP)
    ├── User Service         Auth, profiles, OTP, identity verification   (.NET)
    ├── Discovery Service    Geospatial scan, BLE coordination, filters    (Java)
    ├── Connection Service   Request lifecycle (pending → accepted → expired) (Java)
    ├── Chat Service         MongoDB messages, SignalR hub, TTL expiry     (.NET)
    ├── Rating Service       Submission, trust score recalculation         (Java)
    ├── Notification Service FCM push + SignalR event fan-out              (.NET)
    ├── Report Service       Report queue, escalation — MongoDB            (Java)
    └── Admin Service        Dashboard, review queue, account actions      (Java)
```

Kafka topics:
- `user.verified` → Discovery (add to scan pool)
- `connection.accepted` → Chat (open room)
- `connection.chat-failed` → Connection (compensating: revert to PENDING)
- `chat.closed` → Rating (prompt both users)
- `rating.submitted` → Rating (recalculate trust score)
- `trust.score.updated` → Discovery (update limits/visibility)
- `report.filed` → Report (escalate if threshold hit)

## SOLID Principles — Where We Apply Them

Every service must follow these. When writing code, name the principle in a comment if the reason is non-obvious.

| Principle | Plain English | Where in this project |
|---|---|---|
| **S** — Single Responsibility | One class does one job | `TrustScoreCalculator` only calculates scores — no DB calls, no Kafka. `OutboxRelay` only publishes — no business logic. |
| **O** — Open/Closed | Add new behaviour without changing existing code | Rating tags are defined as an enum/config. Adding a new tag = add to config, not change the validator. |
| **L** — Liskov Substitution | Subtypes must behave like their parent | `IOutboxService` — any implementation (SQL, MongoDB, in-memory for tests) must behave identically to callers. |
| **I** — Interface Segregation | Don't force a class to implement methods it doesn't need | `IChatRoomRepository` and `IMessageRepository` are separate — Chat Service only depends on what it actually uses. |
| **D** — Dependency Inversion | Depend on abstractions, not concrete classes | Services inject `IUserRepository`, not `SqlUserRepository`. Kafka producers inject `IOutboxService`, not `KafkaTemplate` directly. |

## System Design Principles — Where We Apply Them

| Principle | Plain English | Where in this project |
|---|---|---|
| **Database per Service** | Each microservice owns its data, nobody else touches it directly | 9 services, 9 separate DB schemas. Admin Service reads others via HTTP, not direct DB joins. |
| **Event-Driven Architecture** | Services react to things that happened, not commands | Kafka topics carry past-tense events (`connection.accepted`, `chat.closed`), not instructions. |
| **Saga (Choreography)** | Break a distributed transaction into local steps with compensating rollbacks | ConnectionLifecycleSaga: accept → open chat → met → rate. If chat fails → revert connection. |
| **Outbox Pattern** | Write event to DB in same transaction as business data — relay publishes later | Every Kafka producer writes to `outbox` table first. Prevents split-brain between DB and message bus. |
| **Idempotency** | Processing the same message twice produces the same result as once | Every Kafka consumer checks `processed_events` table before acting. Safe for Kafka at-least-once delivery. |
| **Circuit Breaker** | Stop calling a failing service, use fallback, retry after timeout | Discovery Service wraps all calls to User Service in Resilience4j. If User Service is down, returns cached profiles. |
| **Cache-Aside** | Load data into cache on first miss, serve from cache after | Discovery Service: user profiles cached in Redis for 5 min. On miss → fetch from User Service → store. |
| **API Gateway** | Single entry point — handle cross-cutting concerns once | YARP gateway validates JWT, injects identity headers, applies rate limiting. No service does this itself. |
| **Strangler Fig (future)** | Gradually replace parts of the system without big rewrites | Angular PWA → add Capacitor → native app. Same codebase, incremental transformation. |

## Key Domain Rules

- Chat opens **only** after mutual acceptance of a connection request.
- Chat messages are ephemeral — MongoDB TTL index enforces deletion. No history endpoint exists by design.
- Discovery radius default is 1 km (GPS) / 10–30 m (BLE offline fallback).
- BLE offline mode uses rotating short-lived tokens — never raw user IDs broadcast over Bluetooth.
- Women-only mode is a server-side filter predicate; gender is never exposed in API responses to other users.
- Trust score drives both the Trusted badge and request-send throttling. Very low scores suspend from discovery.
- Identity verification requires a base photo. If Google account has no photo, user must upload one before verification is possible.

## Team Architecture

Your role is **Tech Lead / Product Owner** — you review requirements and code. Agents do the implementation.

```
/phase N
  └── Ticket Creator agents produce .claude/tickets/T001.md … T005.md
        └── YOU open tickets in IDE, review requirements
            change  status: draft  →  status: approved

/start-task T001
  └── Developer agent reads ticket + skill files → writes code
        └── YOU review generated code in IDE

/review-task T001
  └── Reviewer agent checks code against ticket spec
        └── APPROVED  →  done
            NEEDS_REWORK  →  Fixer agent fixes issues  →  Reviewer again
                              (auto-loop, max 3 cycles)
```

## Commands

### Starting a phase

**`/phase N`** — creates all ticket files for phase N (one per task). Does nothing else. You then work through tickets one at a time.

```
/phase 0
```

After this runs, open `.claude/tickets/` in your IDE. For each ticket you're happy with, change `status: draft` → `status: approved`.

---

### Working a single ticket

**`/create-ticket T001`** — create one ticket without running the full phase command. Useful when you want to preview one task before deciding to work on it.

**`/start-task T001`** — implement an approved ticket. Reads the ticket file, loads the required skill files, spawns a developer agent. Fails immediately if the ticket isn't approved yet.

**`/review-task T001`** — review the code that was just written. Spawns a reviewer agent that checks the code against every acceptance criterion and architecture rule. If issues are found, automatically spawns a fixer agent and re-reviews. Loops up to 3 times. Reports APPROVED or asks for your help if still failing.

**`/explain-task T001`** — before starting a task, get a deep explanation of what it teaches: which patterns it uses, why each decision was made, what would break without it. Use this to understand a ticket before you approve it.

---

### Checking progress

**`/status`** — shows all 96 tasks with DONE / PENDING status from PROGRESS.md. Also shows which tasks are ready to start (dependencies met) and which are blocked.

---

### Dev utilities (use when needed)

**`/test-service user-service`** — run tests for a named service. Use `all` to run every service. Determines the right test runner (Maven / dotnet test / npm) based on service name.

**`/validate-contracts`** — after any task that adds a Kafka topic or HTTP endpoint, run this. Checks that Kafka schemas, the error envelope shape, JWT header usage, and outbox pattern coverage are consistent across all services.

**`/docker-up`** — starts the full infrastructure stack (SQL Server, MongoDB, Kafka, Redis, Kafka UI, Jaeger). Shows a health summary and local dev URLs when ready.

---

### Typical session

```
/status                   ← see where you left off

/phase 0                  ← creates T001–T005 ticket files
                          ← open .claude/tickets/ in IDE, review, change to approved

/explain-task T001        ← optional: understand it before approving
/start-task T001          ← implement (after you approve the ticket)
/review-task T001         ← review + auto-fix → APPROVED

/start-task T002          ← next ticket, same flow
...

/validate-contracts       ← after each phase completes
```

---

### File reference

| File/Folder | What it is |
|---|---|
| `PROGRESS.md` | Checklist — mark `[x]` when a task is done. Source of truth for `/status`. |
| `.claude/tickets/T00X.md` | Ticket files — created by agents, reviewed and approved by you |
| `.claude/skill-map.md` | Maps each task ID to the skill files its agent needs |
| `.claude/phases/phase-N.md` | Phase manifest — ordered task list and phase goal |
| `.claude/skills/*.md` | Domain knowledge files injected into agent prompts |
| `.claude/agents/*.md` | Agent prompt templates (ticket-creator, task-implementer, reviewer, fixer) |

## Status

Greenfield — no source code yet. Authoritative requirements: [REQUIREMENTS.md](REQUIREMENTS.md). Full task list: [SPECS.md](SPECS.md).
