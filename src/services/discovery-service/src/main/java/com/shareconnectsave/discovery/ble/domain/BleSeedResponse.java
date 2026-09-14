package com.shareconnectsave.discovery.ble.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// Replaces v1's BleTokenResponse. The one and only place the raw seed ever
// appears in this service's outbound traffic: the client stores it locally
// and derives every future rotating broadcast value from it entirely
// offline (see BleTokenServiceImpl's derivation comment) — the server never
// exposes this value again after this one response.
public record BleSeedResponse(@JsonProperty("seed") String seed) {
}
