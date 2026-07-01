# Phase 9 — Report Service (Java)

**Goal:** Report queue with threshold-based escalation. First use of Spring Data MongoDB in a Java service. DLQ pattern on Kafka publish failure.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T051 | Spring Boot + Spring Data MongoDB Setup | java-spring-boot |
| T052 | Report Submission | java-spring-boot |
| T053 | Escalation Threshold Logic | java-spring-boot |
| T054 | Kafka Producer: report.filed | java-spring-boot kafka-outbox |
| T055 | Report Service Docker Image | — |

**Phase complete when:** Reports are stored in MongoDB, threshold logic escalates to Kafka with DLQ fallback, report counts are correct per target user.
