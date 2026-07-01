# Phase 10 — Admin Service (Java)

**Goal:** Admin-only read/write across multiple service databases. Demonstrates Spring Security with a custom filter (reading X-User-Role header, not re-validating JWT). Caffeine cache for dashboard queries.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T056 | Spring Boot + Spring Security Setup | java-spring-boot |
| T057 | Review Queue Endpoints | java-spring-boot |
| T058 | Trust Score Override | java-spring-boot |
| T059 | Account Suspend | java-spring-boot |
| T060 | Dashboard Queries | java-spring-boot |
| T061 | Admin Service Docker Image | — |

**Phase complete when:** Admin-role requests reach endpoints, non-admin requests are 403'd, queue is correctly sorted by report count, stats refresh every 60s.
