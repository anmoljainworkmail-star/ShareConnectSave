package com.shareconnectsave.discovery.client;

import com.shareconnectsave.discovery.scan.domain.UserCardResponse;

import java.util.List;

// Pattern: Dependency Inversion (SOLID-D) — ScanQueryServiceImpl depends on
// this interface, never on UserServiceClientImpl or WebClient directly. The
// concrete bean behind it (a real WebClient-backed implementation today, a
// stub for tests tomorrow) can change without ScanQueryServiceImpl changing
// at all — same shape as ScanSessionService's own interface/impl split.
//
// Top-level package (sibling to scan/, ble/, config/, cache/), not nested
// under scan/: T024 (BLE resolve endpoint, a later ticket) explicitly says
// it returns "user card objects... same shape as GPS nearby results",
// meaning it will reuse this exact client and UserCardResponse too — this is
// a cross-feature collaborator, not something scan/ owns alone.
public interface UserServiceClient {

    // Returns null ONLY for a genuine 404 (user id no longer exists) —
    // callers must treat that as "omit this candidate from the result",
    // never construct a placeholder card themselves for THAT case (this
    // project's "don't fabricate data" rule).
    //
    // T027: a circuit-open or call-failure is different — this method throws
    // in that case (no fallbackMethod is configured on its @CircuitBreaker;
    // see UserServiceClientImpl's class comment) rather than returning null,
    // specifically so callers CAN tell the two situations apart.
    // DiscoveryCacheService is the only caller today, and it is the one
    // place allowed to catch that exception and manufacture a degraded
    // (cached-or-placeholder) UserCardResponse — this interface's contract
    // is still "a real profile, or nothing", the degraded-response decision
    // lives one layer up.
    UserCardResponse getUserCard(Long userId, double distanceKm);

    // Fail-open by contract: an empty list means EITHER "this user has
    // blocked nobody" OR "User Service's /blocks endpoint could not be
    // reached." See UserServiceClientImpl's fallback comment for why those
    // two cases are deliberately indistinguishable to the caller today — a
    // real, pre-existing gap in User Service (no /blocks controller exists
    // yet), not something this client can fix on its own.
    List<Long> getBlockList(Long userId);
}
