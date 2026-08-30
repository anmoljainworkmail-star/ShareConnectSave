# Phase 2 — API Gateway (.NET)

**Goal:** The single entry point for all client traffic. JWT validated once here — no service does it again. Rate limiting applied here — no service duplicates it.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T011 | YARP Gateway Project Setup | dotnet-mvc-controllers |
| T012 | JWT Validation Middleware | dotnet-mvc-controllers |
| T013 | Rate Limiting Middleware | dotnet-mvc-controllers |
| T014 | Gateway Docker Image | — |

**Phase complete when:** Gateway starts, routes to stub services, validates a real Google JWT, and rejects over-limit requests.
