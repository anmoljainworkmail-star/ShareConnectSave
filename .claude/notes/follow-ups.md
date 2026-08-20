# Review Follow-ups

Non-blocking findings from `/review-task` that were APPROVED_WITH_MINOR_ISSUES rather than
sent back for rework. Each entry lists which future task(s) need to act on it.

`ticket-creator.md` reads this file when drafting a ticket for any service listed in
"Affects" — fold the note into that ticket's spec (or acceptance criteria) so it doesn't
require the person approving the ticket to remember a review from a prior phase.

Mark an entry `[resolved: T0XX]` once the affecting task addresses it — don't delete it,
so the audit trail of what was known and when survives.

---

## From T005 (OpenAPI Specs)

1. **Throttle/lockout status codes use 403/409 instead of 429.**
   `connection-service.yaml` `POST /connections` (403 for throttled/suspended) and
   `user-service.yaml` `POST /auth/otp/send` (409 for OTP lockout) don't follow the
   `429 Too Many Requests` convention, and no `ErrorResponse.code` values are defined to
   let a client tell "blocked" apart from "rate-limited."
   Affects: T017 (Phone OTP Verification), T029/T030 (Connection Service request lifecycle),
   T063/T067 (Angular auth + connection request UI — needs distinct messaging per code).
   Action: when implementing, either switch to 429 or — if keeping 403/409 for
   REQUIREMENTS.md reasons — define explicit `code` enum values (`THROTTLED`, `BLOCKED`,
   `OTP_LOCKED`) and document them for the frontend.

2. **`AuthResponse` returns `refresh_token` but no `/auth/refresh` endpoint exists.**
   Likely an intentional Phase 3 scope deferral, not a dropped requirement.
   Affects: T015/T016 (User Service, Google OAuth + JWT Issuance).
   Action: add `POST /auth/refresh` to `user-service.yaml` and implement it in T016,
   or explicitly confirm token refresh is out of scope for this project and remove the
   field instead.

3. **Request schemas don't set `additionalProperties: false`.**
   `GoogleAuthRequest`, `ProfileUpdateRequest`, etc. silently accept unexpected extra
   fields instead of rejecting them at the schema-validation layer.
   Affects: any task implementing request DTOs against these specs (T015+, T021+, T029+).
   Action: low priority — add `additionalProperties: false` to request schemas when
   touching them, no dedicated ticket needed.

## From T006 (SQL Server Database Schemas)

1. **Discovery Service has no Flyway/mssql-jdbc runtime dependency wired up yet**, so the
   `V001__discovery_service_initial_schema.sql` migration cannot actually execute — `pom.xml`
   has no `flyway-core`/`flyway-sqlserver`/`mssql-jdbc`, and `application.properties` has no
   `spring.datasource.*`/`spring.flyway.*` config. This is expected at this stage (T006 is
   schema-only) but must not be forgotten when the project is bootstrapped.
   Affects: T021 (Discovery Service — Spring Boot Project Setup).
   Action: when wiring up the datasource, make sure Flyway runs through the JDBC driver
   (not a raw `sqlcmd` test) so the migration's `SET ARITHABORT ON` etc. actually take
   effect — JDBC defaults `ARITHABORT` to OFF unlike `sqlcmd`, which is exactly the gap
   this migration's SET block exists to close.

2. **Redundant index on `ratings.connection_id`.** `idx_ratings_connection_id` (single-column)
   is now redundant next to the unique composite `idx_ratings_connection_id_rater_id
   (connection_id, rater_id)` — SQL Server can already satisfy a "find by connection_id"
   query using the composite index's leading column. Not incorrect, just unnecessary
   storage/write overhead.
   Affects: T041 (Rating Service — Spring Boot Setup + DB Schema).
   Action: low priority — drop `idx_ratings_connection_id` in a follow-up migration when
   next touching this schema, no dedicated ticket needed.
