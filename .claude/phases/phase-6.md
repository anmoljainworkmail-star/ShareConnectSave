# Phase 6 — Chat Service (.NET)

**Goal:** Ephemeral real-time chat with TTL deletion, SignalR hub, and the saga step that can trigger a compensating transaction. The MongoDB TTL index is the hard privacy guarantee.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T035 | Chat Service Setup + MongoDB | dotnet-minimal-api |
| T036 | SignalR Hub + Real-time Messaging | dotnet-minimal-api |
| T037 | Chat Lifecycle (Open / Auto-close) | dotnet-minimal-api |
| T038 | Kafka Consumer: connection.accepted | dotnet-minimal-api kafka-outbox |
| T039 | Kafka Producer: chat.closed | dotnet-minimal-api kafka-outbox |
| T040 | Chat Service Docker Image | — |

**Phase complete when:** Chat opens on connection.accepted, messages disappear after TTL, Met Successfully triggers chat.closed event, auto-close fires after 2h.
