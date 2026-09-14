package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.UserCardResponse;

import java.util.List;

// Pattern: Dependency Inversion (SOLID-D) — ScanController depends on this
// abstraction, never on ScanQueryServiceImpl directly, the same shape as
// ScanSessionService. The concrete bean behind it can change (e.g. a
// cached-only stub for a test profile) without ScanController ever needing
// to change.
public interface ScanQueryService {

    // Resolves the caller's own active session internally (by userId, the
    // same "identity from a trusted header, never a client-supplied id"
    // convention as every other method on this controller) — throws
    // ScanSessionNotFoundException, reusing the 404 mapping ScanController
    // already has, if the caller has no active scan.
    List<UserCardResponse> findNearby(Long userId);
}
