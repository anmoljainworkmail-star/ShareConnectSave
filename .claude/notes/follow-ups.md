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
