# ShareConnectSave

## TL;DR

A travel companion discovery platform — verified travelers nearby heading the same way find each other, chat briefly, then the platform gets out of the way once they've met. Built as a learning vehicle for polyglot microservices: Java + .NET services talking only through Kafka events, each owning its own database, tied together with Saga/Outbox, SignalR, and an Angular PWA. Currently early — Phase 0 (foundation & contracts) of 14 phases.

## What this is

ShareConnectSave is a travel companion discovery platform — think of it as helping verified travelers who happen to be heading the same way find each other nearby. It only handles discovery, matching, and a temporary chat window; it deliberately stays out of ride booking, payments, and tracking. Once both people mark **Met Successfully**, the chat closes and the platform's job is done.

I'm building this primarily to *learn*, not just to ship — it's a real excuse to get hands-on with distributed microservices across two languages (Java and .NET), Kafka as an event bus, the Saga/Outbox pattern, SignalR, BLE, and an Angular PWA. Everything below is written the way I'd explain it out loud, so I can walk through it cold in an interview.

## How it's put together

The system is nine services behind a single API gateway. An Angular PWA talks to a YARP gateway, which fronts eight microservices — a mix of .NET (User, Chat, Notification) and Java/Spring Boot (Discovery, Connection, Rating, Report, Admin). Each service owns its own database; nobody reaches across into another service's tables. Services don't call each other directly for anything asynchronous — they publish events onto Kafka and react to what other services publish. Realtime stuff (chat messages, live notifications) rides over SignalR with a Redis backplane so it can scale across multiple instances.

The reason for this shape: it's a deliberately "textbook" microservices setup so that every classic distributed-systems problem — data ownership, eventual consistency, retries, duplicate delivery — shows up on purpose and gets solved on purpose, rather than avoided by keeping everything in one app.

## The story so far

### Phase 0 — Foundation & Contracts

**Jul 6, 2026 — we started with the skeleton.** Before any real code could exist, every service needed a place to live. So the first piece of work was scaffolding the whole monorepo: a folder per service (four .NET minimal API projects, five Spring Boot projects, one Angular app), stub Docker Compose entries for each, and shared tooling (`.editorconfig`, `.gitignore`) that works across both the .NET and Java worlds. Nothing in here has business logic yet — it's purely "the folders exist, the projects compile." The point of doing this first and doing it deliberately is that it makes the architecture visible from day one: separate folders per service is the physical manifestation of "database per service" and of keeping a polyglot stack (Java + .NET side by side) from turning into a tangled mess.

**Jul 10, 2026 — then we defined how services would talk to each other, before writing any of the code that actually talks.** Since Kafka is how all seven "reactive" services communicate, we wrote a JSON Schema contract for every one of the seven event types the platform produces (`user.verified`, `connection.accepted`, `chat.closed`, and so on), plus a README explaining who produces each event, who consumes it, and the partition/retention settings. This is "schema-first design" — agreeing on the exact shape of a message *before* either the producing service or the consuming service has a single line of handler code, so a .NET team and a Java team can't silently drift apart on what a field means. Every one of those schemas also carries a mandatory `event_id`, which is the seed of idempotency: because Kafka only guarantees "at least once" delivery, every consumer will eventually need to check "have I already processed this exact event_id?" before acting on a message — otherwise a redelivered message could double-charge a trust score update or reopen a closed chat.

**Where that leaves things right now (as of Jul 10, 2026):** the foundation phase (Phase 0) is in progress — the repo skeleton and the event contracts exist, but we haven't yet written the shared error-response format all services will use, brought up the actual Docker Compose infrastructure (SQL Server, MongoDB, Kafka, Redis), or written the OpenAPI specs for the HTTP-facing endpoints. Those three are next, and only after they land does real service code start (Phase 1 onward: gateway, then User Service, then Discovery, working outward from the services with the fewest dependencies).

## Concepts I can explain cold because of this

- **Monorepo** — one repository, many services, so a cross-service change is one atomic commit instead of coordinating several repos.
- **Polyglot services** — .NET and Java coexist because each service picks whatever runtime suits its job; there's no shared runtime dependency forcing a single language.
- **Database per service** — physically expressed here as separate folders now, separate schemas later; no service is ever allowed to query another's tables directly.
- **Event-Driven Architecture** — Kafka topics are named as past-tense facts (`connection.accepted`), not commands. A producer publishes a fact and has zero knowledge of who's listening or what they'll do about it.
- **Schema-first design** — the contract for a message is written and agreed on before any handler code exists, so producer and consumer can't quietly disagree about a field.
- **Idempotency via `event_id`** — every event carries a UUID specifically so a consumer can recognize "I've already handled this one" and safely ignore a duplicate delivery, which Kafka's at-least-once guarantee makes inevitable.
- **Outbox Pattern (set up now, implemented later)** — the plan is for services to write their outgoing event to their own database in the *same transaction* as the business change, then have a background relay publish it to Kafka — so the DB write and the event can never split apart.

For the full tech stack, the Kafka topic map, and how each SOLID/system-design principle maps onto a specific service, see [CLAUDE.md](CLAUDE.md). For the raw task-by-task checklist, see [PROGRESS.md](PROGRESS.md).

---
_This narrative is extended after each `/push` — new work gets woven into "The story so far" and "Where that leaves things," not tacked on as a separate ticket entry._
