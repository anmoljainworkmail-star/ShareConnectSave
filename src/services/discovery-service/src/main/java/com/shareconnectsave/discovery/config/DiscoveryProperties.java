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
// ScanQueryServiceImpl, ScanCacheService and UserServiceClientImpl now all
// inject this ONE bean through the plain @RequiredArgsConstructor Lombok
// already generates, instead of each hand-writing an explicit constructor
// solely to give @Value something to bind onto.
@ConfigurationProperties(prefix = "discovery")
public record DiscoveryProperties(
        Radius radius,
        double routeOverlapThreshold,
        long departureWindowMinutes,
        Cache cache) {

    public record Radius(double km) {
    }

    public record Cache(
            long nearbyResultTtlSeconds,
            long blocklistTtlMinutes,
            long userProfileTtlMinutes) {
    }
}
