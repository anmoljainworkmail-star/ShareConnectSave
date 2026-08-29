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
