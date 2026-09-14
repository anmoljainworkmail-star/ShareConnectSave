package com.shareconnectsave.discovery.ble;

import com.shareconnectsave.discovery.ble.domain.BleSeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

// Pattern: Repository (GoF/DDD) — same shape as v1's BleTokenRepository and
// ScanSessionRepository: plain collection-like access, no hand-written
// query implementation. Spring Data derives all three methods below from
// their names at startup.
public interface BleSeedRepository extends JpaRepository<BleSeed, Long> {

    // Resolve (BleTokenServiceImpl.resolveTokens): a single "WHERE user_id
    // IN (...)" batch fetch for the candidate ids already narrowed down to
    // scan:active_sessions — mirrors ScanSessionRepository.findAllById's
    // batching in ScanQueryServiceImpl.findNearby, so resolving N candidates
    // never costs N round-trips (no per-candidate N+1 query).
    List<BleSeed> findByUserIdIn(List<Long> userIds);

    // Per-user sweep, called from BleTokenServiceImpl.issueSeed every time a
    // new seed is minted for that user (same "old tokens for this user are
    // deleted" spirit as v1) — narrower and more frequent than
    // BleTokenCleanupTask's own service-wide sweep below, which exists as a
    // backstop for a user who stops requesting new seeds altogether.
    void deleteByUserIdAndExpiresAtBefore(Long userId, Instant cutoff);

    // Service-wide sweep for BleTokenCleanupTask's @Scheduled job — without
    // this, seeds belonging to a user who never calls /scan/ble/seed again
    // would sit in the table forever.
    void deleteByExpiresAtBefore(Instant cutoff);
}
