# T017 — Phone OTP Verification

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

## Send — `POST /auth/otp/send`

```mermaid
flowchart TD
    A["POST /auth/otp/send"] --> B{"JWT valid?"}
    B -- invalid --> B1["401 Unauthorized"]
    B -- valid --> C{"OtpSendPolicy: permit left?"}
    C -- exhausted --> C1["429 Too Many Requests"]
    C -- permit left --> D{"Phone matches +91 regex?"}
    D -- no --> D1["400 INVALID_REQUEST"]
    D -- yes --> E["OtpService.SendOtpAsync"]
    E --> F{"Code sent &lt;30s ago?"}
    F -- yes --> F1["200 sent:true (no-op)"]
    F -- no --> G["Generate code + SHA-256 hash"]
    G --> H["Insert/Update OtpAttempt"]
    H --> I{"Twilio send ok?"}
    I -- fail --> I1["500 Internal Error"]
    I -- ok --> J["200 sent:true"]
```
`ResendCooldownSeconds`(30s) and `CodeExpiryMinutes`(5min) are different windows — cooldown gates resend, expiry gates verify.

## Verify — `POST /auth/otp/verify`

```mermaid
flowchart TD
    A["POST /auth/otp/verify"] --> B{"JWT valid?"}
    B -- invalid --> B1["401 Unauthorized"]
    B -- valid --> C{"Phone regex valid?"}
    C -- no --> C1["400 INVALID_REQUEST"]
    C -- yes --> D{"Code is 6 digits?"}
    D -- no --> D1["400 INVALID_REQUEST"]
    D -- yes --> E{"X-User-Id present?"}
    E -- no --> E1["401 MISSING_IDENTITY"]
    E -- yes --> F["Fetch OtpAttempt"]
    F --> G{"LockedUntil > now?"}
    G -- locked --> G1["429 OTP_LOCKED"]
    G -- not locked --> H{"codeIsValid?"}
    H -- no --> I{"Window expired? &gt;10min since WindowStartedAt"}
    I -- yes --> I1["Reset: WindowStartedAt=now, AttemptCount=1"]
    I -- no --> I2["AttemptCount += 1"]
    I1 --> J{"AttemptCount >= 5?"}
    I2 --> J
    J -- yes --> J0["Set LockedUntil = now+15min"]
    J0 --> J1["429 OTP_LOCKED"]
    J -- no --> J2["400 INVALID_OTP"]
    H -- yes --> K["Fetch User"]
    K --> L{"User exists?"}
    L -- no --> L1["500 Internal Error"]
    L -- yes --> M{"Phone verified on other account?"}
    M -- yes --> M1["409 PHONE_ALREADY_IN_USE"]
    M -- no --> N["Consume code, verify phone"]
    N --> O{"DB unique-index conflict?"}
    O -- yes --> O1["409 PHONE_ALREADY_IN_USE"]
    O -- no --> P["200 phone_verified:true"]
```
Phone conflict is checked twice — a fast-path read (M) then the DB's own unique index (O) — only O is the real guarantee.
