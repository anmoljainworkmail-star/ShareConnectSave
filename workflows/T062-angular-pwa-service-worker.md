# T062 — Angular PWA: Service Worker Install & Request Routing

Quick-glance boxes-and-arrows reference for how a request actually flows through the
service worker. No prose — if you need the "why," see `.claude/tickets/T062.md`.
Generated/updated via `/diagram-task`.

```mermaid
flowchart TD
    subgraph BROWSER["Browser tab"]
        A1["GET / "]
        A2["main.js bootstraps AppModule"]
        A3{"App stable, or 30s elapsed?"}
        A9["Later: page requests a file or /api/**"]
    end

    subgraph ORIGIN["Static origin (nginx / S3+CloudFront)"]
        O1["Serves index.html + hashed bundle"]
        O2["Serves ngsw.json on demand"]
        O3["Serves asset file on demand"]
    end

    subgraph SW["Service Worker (ngsw-worker.js)"]
        S1["Register"]
        S2["Install: fetch ngsw.json manifest"]
        S3["Prefetch assetGroup 'app'<br/>(installMode: prefetch)"]
        S4["Activate, take control"]
        S5{"Intercept request"}
        S6{"Matches an assetGroup?"}
        S7{"Already in Cache Storage?"}
        S8(["Serve from cache"])
        S9["Fetch from origin, cache it"]
        S10{"Matches dataGroup<br/>'/api/**' (freshness)?"}
        S11["Fetch from API Gateway<br/>(timeout: 5s)"]
        S12{"Responded in time?"}
        S13(["Serve live response, cache copy"])
        S14{"Cached copy younger<br/>than maxAge 1h?"}
        S15(["Serve stale cached copy"])
        S16(["Fail — no usable data"])
    end

    subgraph GW["API Gateway container"]
        G1["/api/** route handlers"]
    end

    subgraph UPDATE["SwUpdate — app code (NOT YET BUILT)"]
        U1{{"Interval timer, e.g. every 6h<br/>NOT WIRED UP YET"}}
        U2["checkForUpdate()"]
        U3{"Hash changed vs<br/>installed version?"}
        U4(["versionUpdates: VERSION_READY"])
        U5["Show 'update available' prompt"]
        U6["activateUpdate()"]
        U7(["document.location.reload()"])
    end

    A1 --> O1 --> A2 --> A3
    A3 -->|"stable / 30s"| S1
    S1 --> S2 --> O2
    S2 --> S3 --> O3
    S3 --> S4
    A9 --> S5
    S5 --> S6
    S6 -->|"yes"| S7
    S7 -->|"hit"| S8
    S7 -->|"miss (lazy group,<br/>or new build hash)"| S9
    S9 --> O3
    S6 -->|"no"| S10
    S10 --> S11 --> G1
    S11 --> S12
    S12 -->|"yes"| S13
    S12 -->|"no / timeout"| S14
    S14 -->|"yes"| S15
    S14 -->|"no"| S16

    U1 -.->|"not wired up yet"| U2
    U2 --> O2
    O2 --> U3
    U3 -->|"no"| U1
    U3 -->|"yes"| U4 --> U5 --> U6 --> U7
    U7 -.->|"fresh load, back to top"| A1
```

Asset invalidation is hash-based (a new build changes the file's hash — no TTL involved);
data invalidation is purely time-based (`maxAge`) — the two groups never share an
invalidation mechanism. The `UPDATE` subgraph is the only path that re-checks `ngsw.json`
without a reload — see `.claude/notes/follow-ups.md`'s "From T062" entry #4; nothing in it
is implemented yet, so a currently-open tab never takes this path on its own.
