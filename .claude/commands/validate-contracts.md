Validate cross-service contracts in this project for consistency.

## What to check

### 1. Kafka schemas (`/contracts/kafka/`)
For each topic defined in REQUIREMENTS.md, verify:
- Schema file exists in `/contracts/kafka/`
- Producer service emits exactly the fields declared in the schema (snake_case, correct types)
- Every consumer service deserializes those same field names
- `event_id` (UUID) field is present on every schema — required for consumer idempotency

Expected topics: `user.verified`, `connection.accepted`, `connection.expired`, `connection.chat-failed`, `chat.closed`, `rating.submitted`, `trust.score.updated`, `report.filed`

### 2. HTTP error envelope
Every service must return this exact JSON shape on errors:
```json
{ "code": "...", "message": "...", "traceId": "..." }
```
Check:
- .NET services: `ProblemDetails` middleware or equivalent maps to this shape
- Java services: `@ControllerAdvice` / `GlobalExceptionHandler` maps to this shape
- No service returns a different error structure (stack traces, Spring default whitelabel errors, etc.)

### 3. JWT header contract
No service should validate or decode the JWT. Every service must read identity exclusively from:
- `X-User-Id` (UUID string)
- `X-User-Role` (`user` or `admin`)
- `X-User-Gender` (`female`, `male`, `unspecified`)

Check that no service imports JWT validation libraries for incoming requests.

### 4. Service-to-service HTTP calls
When a Java service calls a .NET service (or vice versa) via WebClient/HttpClient:
- It uses the Docker Compose container name from REQUIREMENTS.md (e.g., `http://user-service:8081`)
- No hardcoded IPs
- The W3C `traceparent` header is propagated on every outbound call

### 5. Outbox pattern coverage
Every service listed as a Kafka producer in REQUIREMENTS.md must have an `outbox` table (or collection) and an `OutboxRelay` background job. List any producer service that publishes directly to Kafka without going through the outbox.

## Output format
Number each issue found. If no issues: say "All contracts consistent." Be specific — include the file path and line reference for every problem.
