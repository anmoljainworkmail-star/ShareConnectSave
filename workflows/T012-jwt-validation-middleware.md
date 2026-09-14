# T012 — JWT Validation Middleware

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

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
