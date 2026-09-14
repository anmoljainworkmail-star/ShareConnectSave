# T023 — GPS Discovery Query

Quick-glance flow diagram — what calls what, in what order, where it branches. Not
documentation; see `.claude/tickets/T023.md` and code comments for the "why."

### `POST /scan/start`

```mermaid
flowchart TD
    A[POST /scan/start] --> B[ScanController.startScan]
    B --> C[ScanSessionServiceImpl.startScan]
    C --> D[ScanSessionRepository.save]
    D --> E[SADD scan:active_sessions]
    E --> F[SET scan:id:gender]
    F --> G[SET scan:id:women_only]
    G --> H[[201 ScanStartResponse]]
```

`PUT /scan/location` → `ScanSessionServiceImpl.updateLocation` → `SET scan:id:location EX 30s` — runs continuously for every session, independent of women_only.

### `GET /scan/nearby`

```mermaid
flowchart TD
    A[GET /scan/nearby] --> B[ScanController.getNearby]
    B --> C[ScanQueryServiceImpl.findNearby]
    C --> D[find caller session: ScanSessionRepository]
    D --> E{ScanCacheService.getCachedNearbyResult}
    E -->|hit| E1[[return cached list]]
    E -->|miss| F[getLocation callerSessionId]
    F --> G{caller location present}
    G -->|no| G1[[return empty list]]
    G -->|yes| H[GET scan:sessionId:women_only]
    H --> I[ScanCacheService.getActiveSessionIds]
    I --> J

    subgraph Pass1[Pass 1 - id + Redis only, per candidate]
        J{candidate == caller}
        J -->|yes, skip| J
        J -->|no| K{women-only enabled}
        K -->|yes| L[GET scan:candidate:gender]
        L --> M{gender == female}
        M -->|no, skip| J
        M -->|yes| N[getLocation candidate]
        K -->|no| N
        N --> O{location present}
        O -->|no, skip| J
        O -->|yes| P[ScanFilterService.distanceKm]
        P --> Q{within radius.km}
        Q -->|no, skip| J
        Q -->|yes, keep id+distance| J
    end

    J -->|ids collected| R[ScanSessionRepository.findAllById batch]

    subgraph Pass2[Pass 2 - SQL + network, per surviving candidate]
        S{SQL row active}
        S -->|no, skip| S
        S -->|yes| T[ScanFilterService.routeOverlap]
        T --> U{overlap >= threshold}
        U -->|no, skip| S
        U -->|yes| V[ScanFilterService.withinDepartureWindow]
        V --> W{within window}
        W -->|no, skip| S
        W -->|yes| X[isBlocked both directions]
        X --> Y{blocked}
        Y -->|yes, skip| S
        Y -->|no| Z[UserServiceClient.getUserCard]
        Z --> AA{circuit open or failure}
        AA -->|yes| AB[getUserCardFallback: cached or null]
        AA -->|no| AC[live profile via WebClient]
        AB --> S
        AC --> S
    end

    R --> S
    S -->|all processed| AD[ScanCacheService.cacheNearbyResult 10s TTL]
    AD --> AE[[200 OK: List UserCardResponse]]
```

Pass 1 only ever touches Redis (never SQL); SQL's `findAllById` only ever sees candidate ids Pass 1 already kept.
