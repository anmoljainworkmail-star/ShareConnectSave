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
    A[Request hits gateway] --> B[Routing match, appsettings.json<br/>tags endpoint with RateLimiterPolicy, if any]
    B --> C[UseJwtValidation<br/>public bypass or inject X-User-Id]
    C --> D{Matched route = OTP send?}
    D -- yes --> E[UseOtpPhoneNumberBuffering<br/>reads phone_number into context.Items]
    D -- no --> F[UseRateLimitHeaders<br/>arms Response.OnStarting]
    E --> F
    F --> G[UseRateLimiter]
    G --> H{GlobalLimiter permit left?<br/>per IP, 100/min}
    H -- exhausted --> K[429 + RATE_LIMIT_EXCEEDED envelope]
    H -- permit left --> I{Named policy attached?<br/>Otp / ConnectionRequest / GoogleAuth}
    I -- none --> M[MapReverseProxy]
    I -- attached --> J{Named policy permit left?}
    J -- exhausted --> K
    J -- permit left --> M
    M --> N[Downstream service]
    K --> O[OnStarting fires:<br/>X-RateLimit-Remaining written]
    N --> O
```

GlobalLimiter and the named policy are checked independently — both must have a permit, not either/or.

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
