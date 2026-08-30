# Workflows

Quick-glance boxes-and-arrows reference for how a request actually flows through the code.
No prose — if you need the "why," see the ticket file or `README.md`'s concepts section.
Generated/updated via `/diagram-task T0XX`.

## Contents

- [T004 — Docker Compose Infrastructure](#t004--docker-compose-infrastructure)
- [T016 — Google OAuth + JWT Issuance](#t016--google-oauth--jwt-issuance)
- [T012 — JWT Validation Middleware](#t012--jwt-validation-middleware)
- [T013 — Rate Limiting Middleware](#t013--rate-limiting-middleware)
- [T014 — Gateway Docker Image](#t014--gateway-docker-image)
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

## T016 — Google OAuth + JWT Issuance

```mermaid
flowchart TD
    A[User clicks Login with Google] --> B[Google Identity Services popup<br/>runs entirely in browser, no BE yet]
    B --> C[FE receives id_token]
    C --> D[POST /auth/google id_token]
    D --> E{id_token blank?}
    E -- yes --> Z1[401 INVALID_GOOGLE_TOKEN]
    E -- no --> F[GoogleTokenValidator.ValidateAsync]
    F --> G[GET Google tokeninfo endpoint]
    G --> H{200 + sub present<br/>+ aud == GOOGLE_CLIENT_ID?}
    H -- no --> Z1
    H -- yes --> I[GoogleTokenPayload:<br/>sub, email, name, picture]
    I --> J{User row exists<br/>for this GoogleId?}
    J -- no --> K[Create User<br/>Status=incomplete, Gender=Unspecified]
    K --> L[UserRepository.AddAsync]
    J -- yes --> M[Reuse existing row unchanged]
    L --> N[JwtIssuer.IssueAccessToken<br/>+ IssueRefreshToken]
    M --> N
    N --> O[Claims: sub=user.Id, role=user,<br/>gender=user.Gender]
    O --> P[200: access_token,<br/>refresh_token, is_new_user]
    P --> Q[FE stores tokens]
    Q --> R[Next request:<br/>Authorization Bearer access_token]
    R --> S[api-gateway verifies via<br/>cached JWKS - see T012 below]
```

RSA keypair is loaded/generated once when `JwtIssuer` is constructed (singleton, app startup) — never per-request or per-issue-call; a new token just reuses the same key.

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

## T014 — Gateway Docker Image

```mermaid
flowchart TD
    A[docker compose build api-gateway] --> A2[Compose reads docker-compose.yml:<br/>build.context + dockerfile: Dockerfile]
    A2 --> A3[Docker build engine<br/>executes Dockerfile]
    A3 --> SG1

    subgraph SG1[Temp build container - discarded after build]
        B[FROM sdk:9.0<br/>has compiler+SDK+NuGet cache]
        B --> C[COPY csproj, dotnet restore<br/>cached if csproj unchanged]
        C --> D[COPY . ., dotnet publish<br/>DLLs written to /app/publish]
    end

    D --> SG2

    subgraph SG2[Runtime image build]
        E[FROM aspnet:9.0<br/>runtime only, no compiler]
        E --> F[apt-get install curl]
        F --> G[COPY --from=build<br/>only /app/publish DLLs]
    end

    G --> H[Image tagged<br/>shareconnectsave/api-gateway:dev]
    H --> I[docker compose up -d api-gateway]

    subgraph SG3[api-gateway container - actually running]
        I --> J[Container starts:<br/>dotnet api-gateway.dll]
        J --> K[Healthcheck timer:<br/>40s start_period, every 30s]
        K --> L[curl -f localhost:8080/health<br/>inside container, no auth header]
        L --> M{JwtValidationMiddleware:<br/>GET + path = /health?}
        M -- yes --> N[Bypass, skip straight to next]
        M -- no --> O[normal JWT flow - see T012]
        N --> P[MapGet /health -> 200 OK]
    end

    P --> Q{curl exit code}
    Q -- 0 --> R[Container: healthy]
    Q -- nonzero --> S[Container: unhealthy,<br/>dependents never start]
```

SG1's container is compile-only and thrown away — nothing in it reaches the tagged image except the DLLs copied out at G. SG3 is the only container that's actually "the api-gateway" a developer thinks of as running; it's spun up fresh from the image, minutes or builds after SG1 ever existed. Before the `/health` bypass, branch M hit "no", got 401'd by its own gateway, and the container sat unhealthy forever.

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
