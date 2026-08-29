# Workflows

Quick-glance boxes-and-arrows reference for how a request actually flows through the code.
No prose — if you need the "why," see the ticket file or `README.md`'s concepts section.
Generated/updated via `/diagram-task T0XX`.

## Contents

- [T004 — Docker Compose Infrastructure](#t004--docker-compose-infrastructure)
- [T012 — JWT Validation Middleware](#t012--jwt-validation-middleware)
- [T013 — Rate Limiting Middleware](#t013--rate-limiting-middleware)
- [/docker-up command flow](#docker-up-command-flow)

---

## T004 — Docker Compose Infrastructure

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

---

## T012 — JWT Validation Middleware

```mermaid
flowchart TD
    A[Request hits gateway] --> B{Public route?<br/>google / otp-send / otp-verify}
    B -- yes --> F[Skip validation entirely]
    B -- no --> C[Fetch JWKS from user-service<br/>cached 24h]
    C --> D{Signature + issuer + audience<br/>+ algorithm valid?}
    D -- no --> E[401 + error envelope]
    D -- yes --> G[Inject X-User-Id / X-User-Role / X-User-Gender]
    F --> H[next middleware]
    G --> H
    H --> I[... rate limiting, then MapReverseProxy ...]
```

---

## T013 — Rate Limiting Middleware

```mermaid
flowchart TD
    A[Request hits gateway] --> B[UseRateLimitHeaders:<br/>eager touch ensures GlobalPolicy<br/>cache entry exists for this IP]
    B --> B2[arms Response.OnStarting]
    B2 --> C{Public route?<br/>google / otp-send / otp-verify}
    C -- yes --> E[Skip JWT validation]
    C -- no --> D{JWT valid?}
    D -- no --> Z1[401 + error envelope]
    D -- yes --> F[Inject X-User-Id / Role / Gender]
    E --> G{OTP send route?}
    F --> G
    G -- yes --> H[UseOtpPhoneNumberBuffering:<br/>buffer body, extract phone_number,<br/>rewind position]
    G -- no --> I[UseRateLimiter]
    H --> I
    I --> J{GlobalLimiter permit left?<br/>per IP, 100/min}
    J -- exhausted --> Z2[429 + RATE_LIMIT_EXCEEDED envelope]
    J -- permit left --> K{Named policy attached to route?<br/>Otp / ConnectionRequest / GoogleAuth}
    K -- none --> M[MapReverseProxy]
    K -- attached --> L{Named policy permit left?}
    L -- exhausted --> Z2
    L -- permit left --> M
    M --> N[Forwarded downstream]
    Z1 --> O[Response starts:<br/>OnStarting fires,<br/>X-RateLimit-Remaining written]
    Z2 --> O
    N --> O
```

GlobalLimiter and any named policy are checked independently — both must have a permit, not either/or. The eager touch in step B (added after review found 401s missing the header) is what makes the header land on Z1 too, since GlobalPolicy's cache entry no longer depends on reaching UseRateLimiter first.

---

## /docker-up command flow

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
