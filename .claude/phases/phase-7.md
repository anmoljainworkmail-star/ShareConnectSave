# Phase 7 — Rating Service (Java)

**Goal:** Trust score calculation — the pure function that drives discovery visibility. Demonstrates Single Responsibility (TrustScoreCalculator does nothing but math) and the self-consuming Kafka pattern (rating.submitted → recalculate → trust.score.updated).

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T041 | Spring Boot Setup + DB Schema | java-spring-boot |
| T042 | Rating Submission Endpoint | java-spring-boot |
| T043 | Trust Score Algorithm | java-spring-boot |
| T044 | Kafka Consumer + Producer | java-spring-boot kafka-outbox |
| T045 | Trust Score Badges + Throttle Enforcement | java-spring-boot |
| T046 | Rating Service Docker Image | — |

**Phase complete when:** Submitting a rating triggers trust score recalculation, badge levels are correct, suspended users disappear from discovery via trust.score.updated consumer.
