package com.shareconnectsave.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Options pattern (same shape as DiscoveryProperties/UserServiceProperties) —
// one bean bound from application.yml's "ble" section, auto-registered by
// DiscoveryServiceApplication's @ConfigurationPropertiesScan.
//
// v2 (T024 rework): token() is replaced by seed() (a long-lived per-scan-
// -session secret, not a 5-minute rotating value) and a new window() is
// added (the rotation cadence, previously conflated with the token's own
// TTL in v1). range() is UNCHANGED from v1 — see the field's own comment
// below, still true today.
@ConfigurationProperties(prefix = "ble")
public record BleProperties(Seed seed, Window window, Range range) {

    // ttlMinutes: the SEED's own lifetime (default several hours — "a
    // realistic scan session length"), deliberately NOT the 5-minute
    // rotation cadence that used to live here as Token.ttlMinutes in v1 —
    // conflating the two was v1's actual bug (see this ticket's "Why this
    // ticket is being reworked" section): a token that expired mid-outage
    // could never be refreshed without the same connectivity that was
    // missing.
    //
    // hmacSecret: kept under this same name/env-var (BLE_TOKEN_HMAC_SECRET,
    // Tech Lead decision — still an accurate description of what it is,
    // just no longer the shared secret this feature signs with). v2's
    // actual per-window signing key is the per-user SEED itself (see
    // BleTokenServiceImpl's derivation method), because the whole point of
    // this rework is a value the CLIENT can also independently reproduce
    // offline from the seed alone — a client that never receives this
    // config value could never match a derivation keyed by it. This field
    // stays bound (and still fails startup loudly if unset, same as
    // DISCOVERY_DB_PASSWORD) purely to avoid an unrelated env var rename in
    // this same rework; it is intentionally unused by the resolve-side math.
    public record Seed(long ttlMinutes, String hmacSecret) {
    }

    // seconds: the rotation cadence a client re-derives a fresh broadcast
    // value on, independent of how long the seed itself remains valid —
    // replaces v1's Token.ttlMinutes concept.
    public record Window(long seconds) {
    }

    // UNCHANGED from v1: BleTokenServiceImpl.resolveTokens still has no live
    // GPS fix to compute a real distanceKm from (BLE resolve only ever
    // receives a list of tokens, never lat/lng) — this config-driven
    // constant (CLAUDE.md's own "10-30m BLE offline fallback" figure) still
    // stands in for a measurement BLE's physical range already guarantees
    // structurally.
    public record Range(double meters) {
    }
}
