# Phase 16 — Cross-Service Gaps for Admin Service (Bonus, beyond the original 96 tasks)

**Goal:** T057 (Admin Service's review queue) and T059 (Account Suspend) were both ticketed
against downstream User Service and Connection Service endpoints that were assumed to exist but
were never actually specified or built — confirmed by reading `contracts/openapi/user-service.yaml`
and `contracts/openapi/connection-service.yaml` directly. This phase closes those gaps so T057
actually works end to end against the real stack and T059 can proceed: User Service gets an
`AccountStatus` field plus three internal-only (Docker-network-reachable) endpoints, Report Service
exposes an already-existing repository query as a real route, and Connection Service gets an
admin-only bulk-expire endpoint so a suspend action can actually clear a user's active connection
requests in one call instead of one-by-one.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T102 | Admin Internal Account-Status Endpoints (user-service) | dotnet-mvc-controllers |
| T103 | Report History by Target Endpoint (report-service) | java-spring-boot |
| T104 | Admin Bulk-Expire Connections Endpoint (connection-service) | java-spring-boot |

**Phase complete when:** admin-service's `UserServiceClient`, `ReportServiceClient`, and the new
`ConnectionServiceClient` (T059) all call real, shipped endpoints with no `KNOWN GAP` comments
remaining, and T059 (Account Suspend) is unblocked to implement against the real stack end to end.
