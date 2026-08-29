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
// Memory bounded (fix for T013 review issue 1): uses IMemoryCache with SlidingExpiration
// sized to each policy's window, preventing unbounded growth from attacker-controlled
// partition keys (e.g., phone numbers in unauthenticated OTP requests). Evicted limiters
// are disposed via deferred PostEvictionCallback (after ~5s delay) to avoid disposing while
// a concurrent reader may still hold a reference fetched from TryGetValue.
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

        if (!_cache.TryGetValue(cacheKey, out RateLimiter? limiter))
        {
            lock (_lock)
            {
                if (!_cache.TryGetValue(cacheKey, out limiter))
                {
                    limiter = new SlidingWindowRateLimiter(new SlidingWindowRateLimiterOptions
                    {
                        PermitLimit = permitLimit,
                        Window = window,
                        SegmentsPerWindow = segmentsPerWindow,
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    });

                    var cacheOptions = new MemoryCacheEntryOptions
                    {
                        SlidingExpiration = window
                    };

                    // Deferred dispose on eviction: RateLimiter holds an internal replenishment Timer
                    // that is a genuine GC root, so undisposed instances survive garbage collection
                    // forever. We must dispose eventually. But immediate disposal inside the eviction
                    // callback would race with a concurrent reader who fetched this reference a moment
                    // before eviction; defer by 5s so any such reader is overwhelmingly unlikely to
                    // still be mid-use (every reader does a fresh TryGetValue immediately before use,
                    // so the real race window is sub-millisecond — 5s is margin, not a proof).
                    cacheOptions.RegisterPostEvictionCallback((key, value, reason, state) =>
                    {
                        if (value is RateLimiter evicted)
                        {
                            _ = Task.Delay(TimeSpan.FromSeconds(5))
                                .ContinueWith(_ => evicted.Dispose(), TaskScheduler.Default);
                        }
                    });

                    _cache.Set(cacheKey, limiter, cacheOptions);
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

        if (!_cache.TryGetValue(cacheKey, out RateLimiter? limiter))
        {
            lock (_lock)
            {
                if (!_cache.TryGetValue(cacheKey, out limiter))
                {
                    limiter = new FixedWindowRateLimiter(new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = permitLimit,
                        Window = window,
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    });

                    var cacheOptions = new MemoryCacheEntryOptions
                    {
                        SlidingExpiration = window
                    };

                    // Deferred dispose on eviction: RateLimiter holds an internal replenishment Timer
                    // that is a genuine GC root, so undisposed instances survive garbage collection
                    // forever. We must dispose eventually. But immediate disposal inside the eviction
                    // callback would race with a concurrent reader who fetched this reference a moment
                    // before eviction; defer by 5s so any such reader is overwhelmingly unlikely to
                    // still be mid-use (every reader does a fresh TryGetValue immediately before use,
                    // so the real race window is sub-millisecond — 5s is margin, not a proof).
                    cacheOptions.RegisterPostEvictionCallback((key, value, reason, state) =>
                    {
                        if (value is RateLimiter evicted)
                        {
                            _ = Task.Delay(TimeSpan.FromSeconds(5))
                                .ContinueWith(_ => evicted.Dispose(), TaskScheduler.Default);
                        }
                    });

                    _cache.Set(cacheKey, limiter, cacheOptions);
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
            return limiter?.GetStatistics()?.CurrentAvailablePermits;
        }

        return null;
    }
}
