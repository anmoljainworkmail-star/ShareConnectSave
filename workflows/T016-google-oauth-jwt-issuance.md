# T016 — Google OAuth + JWT Issuance

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

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
    R --> S[api-gateway verifies via<br/>cached JWKS - see T012 workflow]
```

RSA keypair is loaded/generated once when `JwtIssuer` is constructed (singleton, app startup) — never per-request or per-issue-call; a new token just reuses the same key.
