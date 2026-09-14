# T004 — Docker Compose Infrastructure

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

```mermaid
flowchart TD
    A[docker compose up -d] --> B[sqlserver starts]
    A --> C[mongodb starts]
    A --> D[kafka starts, KRaft mode]
    A --> E[redis starts]
    B --> F{sqlserver healthy?<br/>sqlcmd healthcheck}
    F -- yes --> G[sqlserver-init runs<br/>creates 8 per-service databases]
    D --> H{kafka healthy?<br/>kafka-topics.sh healthcheck}
    H -- yes --> I[kafka-ui starts]
    C --> J[mongodb ready, no init gate]
    E --> K[redis ready, no init gate]
```

`depends_on: condition: service_healthy` is the gate — a dependent container's own start is blocked until Docker reports the healthcheck passing, not just "container running."
