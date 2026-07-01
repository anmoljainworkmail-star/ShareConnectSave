# Phase 14 — Saga + Outbox

**Goal:** Extract the outbox pattern into shared libraries (Java + .NET), wire idempotency across all consumers, and add full saga state tracking with compensating transactions. This phase retrofits all earlier services.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T091 | Outbox Module: shared-java-lib | java-spring-boot kafka-outbox saga |
| T092 | Outbox Middleware: .NET | dotnet-minimal-api kafka-outbox |
| T093 | Apply Outbox to All Java Services | java-spring-boot kafka-outbox saga |
| T094 | Apply Outbox to All .NET Services | dotnet-minimal-api kafka-outbox |
| T095 | Consumer Idempotency (Java) | java-spring-boot kafka-outbox |
| T096 | ConnectionLifecycleSaga State Tracking | java-spring-boot dotnet-minimal-api kafka-outbox saga |

**Phase complete when:** No Java service calls KafkaTemplate directly, no .NET service calls IProducer directly, all consumers are idempotent (duplicate event = no-op), saga_state table tracks connection lifecycle, compensating revert fires on chat-open failure.
