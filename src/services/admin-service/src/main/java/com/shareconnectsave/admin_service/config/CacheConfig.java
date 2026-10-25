package com.shareconnectsave.admin_service.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shareconnectsave.admin_service.dashboard.DashboardStatsResponse;

// Cache-Aside pattern, plain English: a cache sits BESIDE the real data
// source, not automatically in front of it. On a miss, the caller fetches
// from the source and decides for itself whether to write the result into
// the cache; on a hit, the caller reads the cache and skips the source
// entirely. Think of a sticky note you write down after looking up a phone
// number in a phone book: next time someone asks, you read the sticky note
// instead of reopening the phone book — but YOU decide when to write (or
// skip writing) that note, the phone book never updates it for you. This
// project's other cache (Discovery Service's Redis user-profile cache) is
// the same pattern one network hop further out; this one is in-process
// (Caffeine) because admin-service's dashboard is read by exactly this one
// instance, with no need for a cache shared across replicas.
//
// A bare Caffeine Cache<K,V> bean, deliberately NOT Spring's @Cacheable
// annotation (see DashboardStatsServiceImpl's own comment): @Cacheable would
// write the cache on every successful return automatically, with no hook for
// this ticket's "never cache a partial/degraded result" rule — a manual
// cache makes that decision an explicit, inspectable `if` in application
// code instead of hidden annotation magic.
//
// Single fixed key ("dashboard-stats", defined in DashboardStatsServiceImpl):
// GET /admin/stats takes no per-request parameters — every caller asks for
// the exact same aggregate snapshot, so there is exactly one cache entry,
// ever, unlike a per-userId cache keyed by path variable.
@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, DashboardStatsResponse> dashboardStatsCache(DashboardProperties dashboardProperties) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(dashboardProperties.cacheTtlSeconds()))
                .build();
    }
}
