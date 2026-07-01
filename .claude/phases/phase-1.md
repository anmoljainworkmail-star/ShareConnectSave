# Phase 1 — Infrastructure

**Goal:** Set up all database schemas, Redis config, Kafka topics, and the developer environment. Services reference these schemas — they must exist before any service runs migrations.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T006 | SQL Server Database Schemas | java-spring-boot dotnet-minimal-api |
| T007 | MongoDB Setup | — |
| T008 | Redis Setup | — |
| T009 | Kafka Topics Init | kafka-outbox |
| T010 | Scoop + JDK 21 Install Script | — |

**Phase complete when:** All schemas migrate cleanly, all infra containers are healthy, JDK 21 is installable via dev-setup.ps1.
