# Phase 5 — Connection Service (Java)

**Goal:** The state machine for connection requests. First place the ConnectionLifecycleSaga steps are implemented. TTL expiry scheduler demonstrates @Scheduled.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T029 | Spring Boot Setup + DB Schema | java-spring-boot |
| T030 | Request Lifecycle Endpoints | java-spring-boot |
| T031 | Request TTL Expiry Job | java-spring-boot |
| T032 | Kafka Producer: connection.accepted + connection.expired | java-spring-boot kafka-outbox |
| T033 | Kafka Consumer: trust.score.updated | java-spring-boot kafka-outbox |
| T034 | Connection Service Docker Image | — |

**Phase complete when:** PENDING→ACCEPTED→EXPIRED state machine is correct, outbox relay fires connection.accepted to Kafka, TTL expiry runs on schedule.
