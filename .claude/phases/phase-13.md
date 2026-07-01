# Phase 13 — Observability

**Goal:** Full distributed tracing across all services. W3C traceparent propagates from Angular → Gateway → all backend services. Every log line has a trace ID. Jaeger visualizes the full request graph.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T087 | OpenTelemetry: Java Services | java-spring-boot |
| T088 | OpenTelemetry: .NET Services | dotnet-minimal-api |
| T089 | Jaeger in Docker Compose | — |
| T090 | Health Checks Consolidation | java-spring-boot dotnet-minimal-api |

**Phase complete when:** A single user request appears as one trace in Jaeger spanning all services, every service /health returns 200 with dependency statuses.
