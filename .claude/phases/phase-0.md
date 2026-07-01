# Phase 0 — Foundation & Contracts

**Goal:** Create the monorepo structure, all shared contracts, and the Docker infra before any service code is written. Every later phase depends on these.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T001 | Monorepo Scaffold | — |
| T002 | Kafka Topic + Contract Definitions | kafka-outbox |
| T003 | Shared Error Envelope Contract | java-spring-boot dotnet-minimal-api |
| T004 | Docker Compose Infrastructure Services | — |
| T005 | OpenAPI Specs (All Services) | — |

**Phase complete when:** All 5 tasks are `[x]` in PROGRESS.md and `docker compose up` starts all infra containers cleanly.
