package com.shareconnectsave.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Pattern: Options pattern — the same idea as .NET's IOptions<T>, bound via
// builder.Services.Configure<T>(configuration.GetSection("...")) in every
// .NET service in this platform. One strongly-typed, immutable object is
// bound ONCE from application.yml's "discovery" section, instead of every
// class that needs a threshold declaring its own @Value("${discovery.foo}")
// constructor parameter. Spring Boot binds a record's canonical constructor
// directly (no @ConstructorBinding needed since Boot 3), including nested
// records for "discovery.radius.km" and "discovery.cache.*" — so
// ScanQueryServiceImpl and DiscoveryCacheService (T026) now both inject this
// ONE bean through the plain @RequiredArgsConstructor Lombok already
// generates, instead of each hand-writing an explicit constructor solely to
// give @Value something to bind onto.
@ConfigurationProperties(prefix = "discovery")
public record DiscoveryProperties(
        Radius radius,
        double routeOverlapThreshold,
        long departureWindowMinutes,
        Cache cache) {

    public record Radius(double km) {
    }

    // T026: sessionLocationTtlSeconds added alongside the three TTLs T023
    // already bound here — DiscoveryCacheService.cacheSessionLocation reads
    // it instead of the Duration.ofSeconds(30) literal ScanSessionServiceImpl
    // used to hardcode. Kept in this SAME record (not a new "cache.ttls.*"
    // tree) so every Redis TTL this service owns is still bound from exactly
    // one place, matching the Options-pattern rationale above.
    public record Cache(
            long sessionLocationTtlSeconds,
            long nearbyResultTtlSeconds,
            long blocklistTtlMinutes,
            long userProfileTtlMinutes) {
    }
}
