# Review Follow-ups

Non-blocking findings from `/review-task` passes that a future task should still pick up.
Logged here because APPROVED_WITH_MINOR_ISSUES findings don't persist on their own — each
entry names the downstream task(s) whose implementation or ticket should address it.

## From T013 (Rate Limiting Middleware)

1. The fix for the final review blocker (X-RateLimit-Remaining missing on 401 responses)
   made `RateLimitHeadersMiddleware` eagerly create a `GlobalPolicy` cache entry (one
   `SlidingWindowRateLimiter` + internal Timer) for every distinct source IP on every
   request — including requests that 401 before ever reaching the real rate limiter.
   This generalizes the same "attacker-controlled cache growth" shape flagged earlier in
   T013's own review cycles (previously only reachable via OTP phone numbers) to cover
   IPs as well. It's already bounded by the existing `IMemoryCache` sliding-expiration +
   deferred-dispose eviction, so it's not a blocker, but a sustained IP-rotation flood
   (e.g. via a botnet or proxy pool sending junk-auth requests) is worth a memory/load
   test before this is trusted at real scale.
   Affects: T014 (Gateway Docker Image — set an explicit container memory limit and
   confirm the gateway survives an IP-flood load test within it), T087–T090
   (Observability — add a metric/alert on rate-limiter cache size or gateway memory
   growth so this kind of leak-shaped-growth is visible in production, not just reasoned
   about in code review).

2. `RateLimitPartitionRegistry`'s deferred-dispose comment (and design) relies on a
   5-second delay being "overwhelmingly unlikely" to still be in use, not a proven
   guarantee — there's a genuine (if sub-millisecond) TOCTOU gap between a reader's
   `TryGetValue` and its next call on the same `RateLimiter` instance. Acceptable as
   shipped, but if this ever needs hardening, the sliding/fixed window construction and
   deferred-dispose logic in `GetSlidingWindowPartition`/`GetFixedWindowPartition` is
   also duplicated verbatim between the two methods — worth extracting into a shared
   helper at the same time.
   Affects: T014 (Gateway Docker Image — no code change needed, just something for
   whoever next touches this file to be aware of before extending it).

## From T015 (User Service Project + EF Core Setup)

1. The connection-string guards in `Program.cs:15-16` and
   `AppDbContextFactory.cs:38-41` only check for `null` (`?? throw`), not an empty or
   whitespace string. `docker-compose.yml`'s `user-service` block (the file used alone
   in production/CI, per the override file's own header comment) sets
   `ConnectionStrings__UserDb=${USER_DB_CONNECTION}` with no Compose-level `:?` required
   guard — unlike `docker-compose.override.yml`, which does have one. If
   `USER_DB_CONNECTION` is ever unset in a prod/CI environment that uses the base compose
   file alone, the container starts with an empty connection string, the `??` guard
   never fires (empty string is not null), and the failure surfaces later as a much less
   legible EF Core/ADO.NET exception instead of the intended clean
   `InvalidOperationException`. Fix: switch both guards to
   `string.IsNullOrWhiteSpace(connectionString)`, and consider adding the same `:?`
   syntax to `docker-compose.yml:266` itself.
   Affects: T016 (Google OAuth + JWT Issuance — next ticket to touch
   `user-service/Program.cs`; tighten the guard while adding the `/auth/google`
   endpoint).

2. No global exception-handling middleware exists yet in `user-service`. Once T016 wires
   a real endpoint (`POST /auth/google`) on top of `UserRepository`/`AppDbContext`, an
   unhandled EF Core/ADO.NET exception in Development mode can render ASP.NET Core's
   default developer exception page, which can include the connection string (and
   therefore the SQL password) in the stack trace if the exception originates from
   `SqlConnection.Open()`. Also relevant: `AppDbContext`'s unique index on `GoogleId`
   means two concurrent `POST /auth/google` calls for a brand-new user can both pass a
   "does this user exist" check and then race on `AddAsync`, producing an unhandled
   `DbUpdateException` (unique-constraint violation) instead of a clean "account already
   exists" response.
   Affects: T016 (Google OAuth + JWT Issuance — add `UseExceptionHandler`/the shared
   error-envelope middleware before the endpoint goes live, and catch
   `DbUpdateException` around the upsert path specifically).

## From T016 (Google OAuth + JWT Issuance)

1. Still no global exception-handling middleware in `user-service` — this repeats the
   gap flagged in T015's follow-up #2 above, which named T016 as the task to fix it, but
   it shipped again unaddressed. Concretely: two near-simultaneous `POST /auth/google`
   calls for the same brand-new Google account can both pass the "does this user exist"
   check (`UserRepository.GetByGoogleIdAsync` returns `null` for both, a classic
   check-then-act/TOCTOU race), then both call `AddAsync`. The DB-level unique index on
   `GoogleId` (`AppDbContext.cs:40`) correctly prevents a duplicate row, but the losing
   request's `DbUpdateException` is unhandled — `Program.cs` has no
   `app.UseExceptionHandler()`/`AddProblemDetails` wiring at all — so it bubbles up as a
   raw unhandled exception instead of the project's standard `ErrorResponse` envelope. In
   `ASPNETCORE_ENVIRONMENT=Development` (how `docker-compose.override.yml` runs this
   service) this can surface exception type/message/stack trace to the caller.
   Affects: T017 (next ticket to touch `user-service/Program.cs` — wire
   `UseExceptionHandler`/`AddProblemDetails` per the `dotnet-mvc-controllers` skill before
   adding new endpoints, and add a `catch (DbUpdateException)` — or re-fetch-and-treat-as-
   existing-user — around the upsert path in `AuthController.GoogleSignIn`), T087–T090
   (Observability — same middleware gap means unhandled exceptions across user-service
   aren't normalized for logging/tracing either).

## From T019 (Identity Verification Badge)

1. `IdentityVerificationService.VerifyIdentityAsync` (`Services/IdentityVerificationService.cs:43`)
   only checks that the uploaded `selfie` is non-null/non-empty — it has no `MaxSizeBytes`
   cap and no `image/*` content-type check, unlike the sibling `UserProfileService.UploadPhotoAsync`
   (T018), which enforces a 5 MB limit and rejects non-image content types (including
   `image/svg+xml` specifically). In real-Azure mode this mostly self-corrects (a
   non-image file just fails Azure's face-detect step and comes back as a mismatch), but
   in stub mode (`IDENTITY_VERIFY_STUB=true`) any file of any type/size up to the ASP.NET
   Core multipart form default is accepted and unconditionally marked verified.
   Affects: T082 (Unit Tests: .NET — add coverage asserting the selfie upload path
   rejects oversized/non-image files the same way `UploadPhotoAsync` does, and tighten
   `VerifyIdentityAsync` to match T018's validation pattern while writing those tests).

2. `IdentityVerification.cs:20` defaults `Status = "Pending"` (PascalCase) but
   `IdentityVerificationService.cs:92` writes `"verified"`/`"failed"` (lowercase) on every
   actual insert. Not a live bug today — the default is never persisted since every
   insert here explicitly sets `Status` — but it's a casing landmine: any future query or
   filter written against one casing convention (e.g. `WHERE Status = 'Pending'`) will
   silently miss rows written under the other.
   Affects: T082 (Unit Tests: .NET — assert the actual persisted casing convention so a
   future change to either the default or the write path is caught by a test instead of
   discovered in a query), T057 (Admin Service Review Queue Endpoints — if a future
   `GET /admin/users/:id` view is extended to surface identity verification history, make
   the status comparison case-consistent with what T019 actually writes to the DB).

## From T020 (Kafka Producer: user.verified event on activation)

1. `contracts/kafka/user.verified.schema.json`'s human-readable `description` field says
   this event is "Published by User Service when a user completes identity verification."
   That wording is stale/wrong — cross-checked against `REQUIREMENTS.md`'s
   `UserOnboardingSaga` (`Google auth -> phone verified -> profile complete ->
   user.verified published`), the actual trigger is onboarding completion
   (`User.IsOnboardingComplete` flipping to true in `OtpService.VerifyOtpAsync`), not
   `User.IdentityBadge` (T019's separate, optional, later signal). Not fixed as part of
   this ticket since no consumer exists yet to be affected by the mismatch either way.
   Affects: whichever future ticket next touches this schema file (or Discovery Service's
   own T025 consumer, Phase 4) — correct the `description` text to match the real trigger
   before anyone reads it as documentation of current behavior.

2. The same schema declares `user_id` as `"type": "string", "format": "uuid"`, but
   `User.Id` in this codebase is actually a `long` (BIGINT IDENTITY — see `User.cs`'s own
   comment on why UUID was deliberately rejected for primary keys). The producer
   (`KafkaUserVerifiedEventPublisher`) satisfies the schema's `"type": "string"` by
   serializing `user.Id.ToString()`, but that string will never actually be a UUID —
   informational only, no ID type or schema change intended.
   Affects: Discovery Service's T025 consumer (Phase 4) — deserialize `user_id` as a
   plain string, not a UUID type, on the Java side.

3a. `KafkaUserVerifiedEventPublisher.PublishUserVerified` (`Events/KafkaUserVerifiedEventPublisher.cs:71`)
   has no `try/catch` around the `_producer.Produce(...)` call itself. `Produce()` normally
   only enqueues locally and returns immediately (network failures surface later via the
   delivery-report callback, which does log), but it can throw synchronously if librdkafka's
   internal send buffer fills (default `queue.buffering.max.messages` = 100,000) — which would
   require ~100,000 queued verification events during one continuous Kafka outage to trigger.
   Not realistic at this app's current traffic, so not a blocker, but it's the one path where
   AC3's "never fail the HTTP request" promise could theoretically break, and it's a one-line fix.
   Affects: T094 (Apply Outbox to All .NET Services — Phase 14, when this direct producer is
   replaced by an outbox-backed one, carry the same try/catch discipline into the relay's own
   send path), T082 (Unit Tests: .NET — add a test asserting a synchronous `Produce()` throw
   never propagates out of `PublishUserVerified`).

3b. `docker-compose.override.yml`'s per-service `${KAFKA_BOOTSTRAP:-kafka:9092}` inline
   fallback defaults (discovery-service, connection-service, chat-service, rating-service,
   report-service, admin-service) are the same wrong PLAINTEXT-listener value fixed for
   user-service in this ticket (`.env.example` and `docker-compose.yml`'s `user-service`
   block both now default to `kafka:29092`, the INTERNAL listener — see the `kafka`
   service's `KAFKA_CFG_ADVERTISED_LISTENERS` comment for why a container-to-container
   client needs that address, not `kafka:9092`). Left untouched here since none of those
   services exist yet as real Kafka clients — flagging on purpose so each is fixed when
   its own ticket actually wires up a producer/consumer, rather than silently carried
   forward and only caught live one service at a time.
   Affects: T0xx (Discovery Service Kafka wiring), and the equivalent first-Kafka-client
   ticket for Connection/Chat/Rating/Report/Admin Service, whichever lands first for each.
