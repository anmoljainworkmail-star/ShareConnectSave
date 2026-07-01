# Phase 12 — Testing

**Goal:** Unit and integration test coverage. Testcontainers for real-database integration tests. Playwright E2E covering the three critical user journeys. k6 load test validating P95 constraint.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T081 | Unit Tests: Java Services | java-spring-boot |
| T082 | Unit Tests: .NET Services | dotnet-minimal-api |
| T083 | Integration Tests: Java (Testcontainers) | java-spring-boot |
| T084 | Integration Tests: .NET (Testcontainers) | dotnet-minimal-api |
| T085 | E2E Tests (Playwright) | angular-pwa |
| T086 | Load Test: Discovery Query (k6) | — |

**Note:** T073–T080 are reserved in the numbering — not currently assigned.

**Phase complete when:** All unit tests pass, integration tests pass against real containers, Playwright happy-path passes, k6 confirms P95 ≤500ms at 50VU.
