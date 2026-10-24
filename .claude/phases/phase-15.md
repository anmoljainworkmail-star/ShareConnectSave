# Phase 15 — Transport Layer: Connector Tuning (Bonus, beyond the original 96 tasks)

**Goal:** Everything ngrok doesn't teach you. ngrok only solves "how does the public internet
reach my laptop" — it's a tunnel, nothing more. This phase is what happens between that tunnel
and your own code: Tomcat's and Kestrel's own connector layer (accept queue, worker thread pool,
connection limits, keep-alive, graceful shutdown), tuned and proven on one representative Java
service and the .NET gateway, then deliberately broken and fixed under real load so the settings
mean something observed, not just something read in a YAML file.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T097 | Tomcat Connector Tuning (discovery-service) | java-spring-boot |
| T098 | Kestrel Limits Tuning (api-gateway) | dotnet-mvc-controllers |
| T099 | Load Test: Connector Saturation (k6) | — |
| T100 | Graceful Shutdown & Connection Draining | java-spring-boot, dotnet-mvc-controllers |
| T101 | Where ngrok Actually Sits (write-up) | — |

**Phase complete when:** discovery-service's and api-gateway's connector settings are explicit
and env-var-driven (not framework defaults), a k6 run demonstrably saturates and then clears the
connector under load with before/after evidence captured, a SIGTERM mid-request is handled
gracefully on both services, and `docs/request-path.md` correctly draws the line between what
ngrok does and what this phase's tuning actually controls.
