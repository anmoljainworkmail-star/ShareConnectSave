# Phase 17 — Cross-Service Gaps for Admin Service — Dashboard Stats (Bonus, beyond the original 96 tasks)

**Goal:** T060 (Admin Service's dashboard) was ticketed against four downstream services'
aggregate-stats endpoints that were assumed to exist but were never actually specified or built —
confirmed by reading `contracts/openapi/user-service.yaml`, `discovery-service.yaml`,
`connection-service.yaml`, and `report-service.yaml` directly against each of T060's 5 metrics,
the same shape of gap Phase 16 closed for T057/T059. This phase closes those gaps so T060 can
ship against real data for all 5 metrics instead of 3 stubbed ones: User Service gets an
internal-only active-user count, Discovery Service gets an admin-only scans-today count,
Connection Service gets an admin-only connections-accepted-today count, and Report Service gets a
real top-reported-users aggregate (not a reuse of the escalation queue, which answers a different
question).

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T105 | Admin Stats Endpoint — Total Active Users (user-service) | dotnet-mvc-controllers |
| T106 | Admin Stats Endpoint — Total Scans Today (discovery-service) | java-spring-boot |
| T107 | Admin Stats Endpoint — Total Connections Accepted Today (connection-service) | java-spring-boot |
| T108 | Top Reported Users Endpoint (report-service) | java-spring-boot |

**Phase complete when:** admin-service has a real `getStats()`/`getTopReported()` call against
each of the four services with no stubbed metric remaining, and T060 (Dashboard Queries) is
unblocked to implement against the real stack end to end.
