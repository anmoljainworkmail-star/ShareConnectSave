package com.shareconnectsave.discovery.ble;

import com.shareconnectsave.discovery.scan.domain.UserCardResponse;

import java.util.List;

// Pattern: Dependency Inversion (SOLID-D) — BleTokenController depends on
// this abstraction, never on BleTokenServiceImpl, same shape as
// ScanQueryService/ScanSessionService.
//
// v2 (T024 rework): issueToken (a 5-minute rotating value, minted fresh on
// every server round-trip) is replaced by issueSeed (a long-lived secret,
// minted once per scan session) — see "Why this ticket is being reworked"
// in the ticket for why v1's design broke exactly when offline support was
// supposed to help most.
public interface BleTokenService {

    // Generates and persists a new seed for this user, deleting that same
    // user's already-expired seeds first (see
    // BleSeedRepository.deleteByUserIdAndExpiresAtBefore). Returns the raw
    // seed — the ONLY point in this service where the raw value exists
    // outside of the moment it was generated; the client must derive every
    // future rotating broadcast value from it locally, entirely offline,
    // and must never have it logged or re-exposed.
    String issueSeed(Long userId);

    // Silently omits any submitted value that matches no active candidate's
    // current-or-previous rotation window — never throws, matching this
    // ticket's "idempotent, silent failure" acceptance criterion. Returned
    // cards are User Service lookups behind the same Circuit Breaker-guarded
    // UserServiceClient the GPS query path uses. Also silently omits any
    // candidate the caller has blocked, or who has blocked the caller — same
    // bidirectional check ScanQueryServiceImpl.isBlocked already applies to
    // GPS discovery results.
    List<UserCardResponse> resolveTokens(Long callerUserId, List<String> rawTokens);
}
