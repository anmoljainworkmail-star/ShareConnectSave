package com.shareconnectsave.discovery.ble.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

// Raw tokens as collected offline over Bluetooth — no session/location data,
// unlike GPS's ScanLocationRequest, because BLE resolve is not a live
// position update. Validated non-empty rather than silently accepting a
// pointless call with nothing to resolve.
public record BleResolveRequest(@JsonProperty("tokens") @NotEmpty List<String> tokens) {
}
