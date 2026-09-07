using System.Threading.RateLimiting;
using Microsoft.Extensions.Caching.Memory;

namespace api_gateway.RateLimiting;

// Dependency Inversion (D in SOLID): the public interface is IRateLimitPartitionRegistry.
// This lets callers depend on the abstraction, not the concrete implementation.
public interface IRateLimitPartitionRegistry
{
    RateLimitPartition<string> GetSlidingWindowPartition(
        string policyName,
        string partitionKey,
        int permitLimit,
        TimeSpan window,
        int segmentsPerWindow);

    RateLimitPartition<string> GetFixedWindowPartition(
        string policyName,
        string partitionKey,
        int permitLimit,
        TimeSpan window);

    long? GetRemainingPermits(string policyName, string partitionKey);
}

// The gap this class exists to close: Microsoft.AspNetCore.RateLimiting only ever
// tells the caller "accepted" or "rejected" for a given request — there is no public
// API to ask "how many permits are left for partition key K" after the decision is
// made, which is exactly what X-RateLimit-Remaining needs (confirmed against the
// framework docs: RateLimitLease metadata only ever carries ReasonPhrase / RetryAfter,
// never a remaining-permit count).
//
// The tempting workaround — keep a second, hand-rolled counter alongside the real
// limiter — is exactly what the ticket forbids ("do not implement rate limiting logic
// manually with counters/dictionaries"), and it would drift from the real limiter's
// state at window boundaries besides. Instead, this registry constructs the one real
// System.Threading.RateLimiting limiter per (policy, partition key) pair itself, hands
// that SAME instance to the rate limiter middleware via RateLimitPartition.Get(...),
// and later asks that SAME instance for its own statistics. One source of truth, read
// from two places (the accept/reject decision, and the header) — never two counters
// that could disagree.
//
// Fix (found during integration testing — reproduced live as an
// ObjectDisposedException crashing every request after ~65s of idle traffic
// for a given partition key): this used to expire+dispose limiters out of
// this class's own IMemoryCache after each policy's window elapsed. That
// doesn't work, and can't be made to work by tuning the delay — the REAL
// rate-limiting decision is made by System.Threading.RateLimiting's own
// PartitionedRateLimiter (constructed in Program.cs). An earlier version of
// this fix assumed that framework-internal cache never disposes anything on
// its own — it does: PartitionedRateLimiter runs its own periodic idle-
// partition cleanup, disposing a partition's limiter once it goes unused for
// a while, specifically so a high-cardinality partition key space (IPs,
// phone numbers) doesn't grow its OWN internal cache unboundedly. That means
// TWO independent caches (this class's, and the framework's own) can each
// decide to dispose the very same RateLimiter instance, on two different,
// uncoordinated schedules — whichever one disposes it "last" leaves the
// other holding a dead reference.
//
// Trying to keep the two caches' *lifetimes* in sync (e.g. by never
// expiring our own side either) doesn't work, because we don't control when
// the framework's side disposes its copy. The only correct fix is to never
// trust a cached reference is still alive without checking: every read
// probes the cached limiter with a cheap, harmless call
// (RateLimiter.GetStatistics(), which throws ObjectDisposedException on a
// disposed instance and does nothing else) before handing it back or reusing
// it. A disposed instance is treated exactly like a cache miss — a fresh
// RateLimiter is created and cached in its place — rather than being handed
// to the framework (which would crash on its very next use) or read for
// GetRemainingPermits (which would crash the response mid-flight, as
// happened live: RateLimitHeadersMiddleware's OnStarting callback threw
// while Kestrel was already flushing headers, aborting the response
// entirely instead of producing even a clean error).
//
// Correction (caught in review): an earlier pass at this fix removed this
// class's own SlidingExpiration entirely, on the mistaken belief that
// bounding OUR cache no longer mattered since "the framework doesn't bound
// its own cache either." That premise was wrong — the crash two paragraphs
// up is proof the framework DOES bound its own cache via periodic idle
// cleanup. Leaving ours fully unbounded reintroduced exactly the
// unbounded-memory-growth risk the original SlidingExpiration existed to
// prevent (T013 review issue 1) — this class is keyed by IP (GlobalPolicy)
// and phone number (OtpSendPolicy), both attacker-influenceable on an
// unauthenticated route. SlidingExpiration is restored below, but WITHOUT
// any disposal callback — eviction here now only ever drops OUR OWN
// tracking reference, never calls Dispose(). That's safe regardless of
// whether the framework is still using its own copy at that moment: if the
// framework disposes its copy first, this cache's (still-live-looking, but
// about to expire) reference just goes stale a little longer, harmlessly;
// if this cache's SlidingExpiration evicts first, the framework's own copy
// is completely unaffected since nothing here ever touches it. Either way,
// the underlying Timer that keeps a RateLimiter alive as a GC root only
// ever gets stopped by the framework's OWN idle cleanup disposing it — this
// class no longer takes on disposal responsibility for an object a second,
// uncoordinated cache also holds a reference to.
public sealed class RateLimitPartitionRegistry : IRateLimitPartitionRegistry
{
    private readonly IMemoryCache _cache;
    private static readonly object _lock = new();

    public RateLimitPartitionRegistry(IMemoryCache cache)
    {
        _cache = cache;
    }

    public RateLimitPartition<string> GetSlidingWindowPartition(
        string policyName,
        string partitionKey,
        int permitLimit,
        TimeSpan window,
        int segmentsPerWindow)
    {
        var cacheKey = $"{policyName}:sliding:{partitionKey}";

        if (!_cache.TryGetValue(cacheKey, out RateLimiter? limiter) || IsDisposed(limiter))
        {
            lock (_lock)
            {
                if (!_cache.TryGetValue(cacheKey, out limiter) || IsDisposed(limiter))
                {
                    limiter = new SlidingWindowRateLimiter(new SlidingWindowRateLimiterOptions
                    {
                        PermitLimit = permitLimit,
                        Window = window,
                        SegmentsPerWindow = segmentsPerWindow,
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    });

                    // Bounded (see this class's header comment "Correction"
                    // paragraph): SlidingExpiration caps how many distinct
                    // partition keys this cache can accumulate, same as
                    // before the ObjectDisposedException fix — but with NO
                    // disposal callback this time. Eviction only ever drops
                    // our own tracking reference; the IsDisposed check above
                    // is what handles the framework disposing its own copy
                    // out from under us, independently of this expiration.
                    _cache.Set(cacheKey, limiter, new MemoryCacheEntryOptions { SlidingExpiration = window });
                }
            }
        }

        return RateLimitPartition.Get(partitionKey, _ => limiter);
    }

    public RateLimitPartition<string> GetFixedWindowPartition(
        string policyName,
        string partitionKey,
        int permitLimit,
        TimeSpan window)
    {
        var cacheKey = $"{policyName}:fixed:{partitionKey}";

        if (!_cache.TryGetValue(cacheKey, out RateLimiter? limiter) || IsDisposed(limiter))
        {
            lock (_lock)
            {
                if (!_cache.TryGetValue(cacheKey, out limiter) || IsDisposed(limiter))
                {
                    limiter = new FixedWindowRateLimiter(new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = permitLimit,
                        Window = window,
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    });

                    // Bounded, no disposal callback — see GetSlidingWindowPartition's
                    // matching comment and this class's header comment for why.
                    _cache.Set(cacheKey, limiter, new MemoryCacheEntryOptions { SlidingExpiration = window });
                }
            }
        }

        return RateLimitPartition.Get(partitionKey, _ => limiter);
    }

    // Sliding vs. Fixed Window Rate Limiting: whichever algorithm is in play,
    // RateLimiterStatistics.CurrentAvailablePermits is the framework's own live
    // "remaining" number for that specific limiter instance — not a re-derived
    // approximation computed on our side.
    public long? GetRemainingPermits(string policyName, string partitionKey)
    {
        var slidingCacheKey = $"{policyName}:sliding:{partitionKey}";
        var fixedCacheKey = $"{policyName}:fixed:{partitionKey}";

        if (_cache.TryGetValue(slidingCacheKey, out RateLimiter? limiter) ||
            _cache.TryGetValue(fixedCacheKey, out limiter))
        {
            // Fix (found live: this exact call threw ObjectDisposedException
            // from inside RateLimitHeadersMiddleware's Response.OnStarting
            // callback, which Kestrel was already mid-flush on — aborting the
            // whole response instead of just leaving the header off). The
            // framework's own idle-partition cleanup (see this class's header
            // comment) can dispose the underlying RateLimiter at any time,
            // independent of this cache — a disposed instance here just means
            // "no live stats to report," not a bug worth crashing the
            // response over, so the header is simply omitted instead.
            try
            {
                return limiter?.GetStatistics()?.CurrentAvailablePermits;
            }
            catch (ObjectDisposedException)
            {
                return null;
            }
        }

        return null;
    }

    // No IsDisposed property exists on RateLimiter to check directly, so this
    // probes with GetStatistics() — a cheap, side-effect-free call that
    // throws ObjectDisposedException on a disposed instance and does nothing
    // else on a live one. Used before ever handing a cached instance back to
    // a caller, so a disposed entry is treated as a cache miss instead of a
    // ticking time bomb for whoever uses it next.
    private static bool IsDisposed(RateLimiter? limiter)
    {
        if (limiter is null)
        {
            return false;
        }

        try
        {
            limiter.GetStatistics();
            return false;
        }
        catch (ObjectDisposedException)
        {
            return true;
        }
    }
}
