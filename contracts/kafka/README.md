# Kafka Topic Contracts — ShareConnectSave

This directory is the single source of truth for every Kafka message shape used in the platform.
Both .NET and Java services validate against these schemas. Unknown fields are ignored on the consumer side; missing required fields throw at deserialisation time.

All field names are `snake_case`. Every schema uses JSON Schema draft-07. Every message carries an `event_id` (UUID v4) — this is the idempotency key that enables every consumer to deduplicate safely under Kafka's at-least-once delivery guarantee.

---

## Topics

| Topic | Producer | Consumers | Partitions | Retention |
|---|---|---|---|---|
| `user.verified` | User Service | Discovery Service | 3 | 7 days |
| `connection.accepted` | Connection Service | Chat Service, Notification Service | 3 | 7 days |
| `connection.expired` | Connection Service | Notification Service | 3 | 7 days |
| `chat.closed` | Chat Service | Rating Service, Notification Service | 3 | 7 days |
| `rating.submitted` | Rating Service | Rating Service (self — recalculate trust score) | 3 | 7 days |
| `trust.score.updated` | Rating Service | Discovery Service | 3 | 7 days |
| `report.filed` | Report Service | Admin Service | 3 | 7 days |

---

## Schema Files

| File | Topic |
|---|---|
| `user.verified.schema.json` | `user.verified` |
| `connection.accepted.schema.json` | `connection.accepted` |
| `connection.expired.schema.json` | `connection.expired` |
| `chat.closed.schema.json` | `chat.closed` |
| `rating.submitted.schema.json` | `rating.submitted` |
| `trust.score.updated.schema.json` | `trust.score.updated` |
| `report.filed.schema.json` | `report.filed` |

---

## Design Decisions

### Why past-tense topic names
Topics carry facts, not commands. `connection.accepted` means "this happened". A consumer decides what to do with that fact — the producer has no knowledge of its subscribers. This decoupling is the core principle of event-driven architecture.

### Why event_id is on every message
Kafka guarantees at-least-once delivery. In failure scenarios (consumer processed the message but crashed before committing the offset), the same message is redelivered. Every consumer stores the `event_id` in a `processed_events` table and skips messages it has already handled. Without `event_id`, there is no safe deduplication key.

### Why schemas live here and not inside each service
Schema-first design. Both .NET and Java teams agree on the contract before writing a single line of handler code. If schemas lived inside a service, the other language's team would reverse-engineer the shape from runtime behaviour — which diverges silently over time.

### Why 3 partitions
Three partitions allow up to 3 consumers in a consumer group to process in parallel without message reordering within a partition key. The partition key is the subject entity ID (e.g., `user_id` for `user.verified`, `connection_id` for `connection.accepted`). Events for the same entity are always processed in order.

### Why 7-day retention
Seven days gives the Notification Service and Admin Service enough time to catch up after a rolling deployment, a consumer outage, or a bug fix that requires replaying recent events. Chat messages use MongoDB TTL for ephemerality — Kafka retention is a separate concern and is not the persistence layer.

---

## Outbox Pattern — How producers publish safely

Services do not call Kafka directly. In the same database transaction that writes the business record, the service also inserts a row into its local `outbox` table. A background relay (polling every 500 ms) reads unprocessed rows and publishes them to Kafka. Once Kafka acknowledges receipt, the row is marked `processed`.

This guarantees atomicity: either the business record and the outbox row both commit, or neither does. The relay handles retries. If publish fails after 3 retries, the `report.filed` topic routes to `report.filed.dlq` for manual inspection.

Broker configuration (partition count, replication factor, retention settings) is defined in T004 Docker Compose infrastructure — not here.
