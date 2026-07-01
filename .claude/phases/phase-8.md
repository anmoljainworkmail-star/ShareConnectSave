# Phase 8 — Notification Service (.NET)

**Goal:** Fan-out layer — receive Kafka events, push FCM notifications for background delivery, and real-time SignalR events for foreground. Shares the Redis SignalR backplane with Chat Service.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T047 | FCM Setup | dotnet-minimal-api |
| T048 | SignalR Notification Hub | dotnet-minimal-api |
| T049 | Kafka Consumers | dotnet-minimal-api kafka-outbox |
| T050 | Notification Service Docker Image | — |

**Phase complete when:** Receiving connection.accepted fires FCM push to recipient device and emits SignalR event to the connected frontend.
