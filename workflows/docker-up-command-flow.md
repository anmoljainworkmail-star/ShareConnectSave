# /docker-up command flow

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

```mermaid
flowchart TD
    A["/docker-up invoked"] --> B{docker info succeeds?}
    B -- no --> C[Say Docker Desktop not running. Stop.]
    B -- yes --> D[docker compose up -d]
    D --> E[Poll docker compose ps every 5s, up to 90s]
    E --> F{All containers healthy?}
    F -- yes, within 90s --> G[Print status table + local dev URLs]
    F -- no, 90s elapsed --> H[Print unhealthy container logs, last 50 lines]
```
