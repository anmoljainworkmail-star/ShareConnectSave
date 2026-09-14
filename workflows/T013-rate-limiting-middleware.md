# T013 — Rate Limiting Middleware

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

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
