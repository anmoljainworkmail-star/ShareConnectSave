# Phase 4 — Discovery Service (Java)

**Goal:** The geospatial core — GPS query, BLE tokens, Redis caching, and circuit breaker. First Java Spring Boot service. Demonstrates Resilience4j, Cache-Aside, and spatial queries.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T021 | Spring Boot Project Setup | java-spring-boot |
| T022 | Geospatial Schema + Scan Session | java-spring-boot |
| T023 | GPS Discovery Query | java-spring-boot |
| T024 | BLE Token Generation | java-spring-boot kafka-outbox |
| T025 | Kafka Consumer: user.verified + trust.score.updated | java-spring-boot kafka-outbox |
| T026 | Redis Caching Layer | java-spring-boot |
| T027 | Resilience4j Circuit Breaker | java-spring-boot |
| T028 | Discovery Service Docker Image | — |

**Phase complete when:** GPS discovery query returns filtered results within 500ms P95, BLE tokens resolve correctly, circuit breaker trips on User Service failure.
