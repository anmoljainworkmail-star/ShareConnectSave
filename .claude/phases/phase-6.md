# Phase 6 — Chat Service (.NET)

**Goal:** Ephemeral real-time chat with TTL deletion, SignalR hub, and the saga step that can trigger a compensating transaction. The MongoDB TTL index is the hard privacy guarantee.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T035 | Chat Service Setup + MongoDB | dotnet-mvc-controllers |
| T036 | SignalR Hub + Real-time Messaging | dotnet-mvc-controllers |
| T037 | Chat Lifecycle (Open / Auto-close) | dotnet-mvc-controllers |
| T038 | Kafka Consumer: connection.accepted | dotnet-mvc-controllers kafka-outbox |
| T039 | Kafka Producer: chat.closed | dotnet-mvc-controllers kafka-outbox |
| T040 | Chat Service Docker Image | — |

**Phase complete when:** Chat opens on connection.accepted, messages disappear after TTL, Met Successfully triggers chat.closed event, auto-close fires after 2h.
