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

    // Bug fix (testing-bugs-pending.md #7): this used to be
    // deleteByUserIdAndExpiresAtBefore(userId, now) — pruning only this
    // user's ALREADY-EXPIRED seeds, which let a still-valid prior seed keep
    // coexisting with a freshly-issued one (two valid seeds for the same
    // user at once, neither ever explicitly superseded). issueSeed's own
    // intent — "exactly one live seed per user" — needs every PRIOR seed
    // gone the moment a new one is minted, valid or not, not just the
    // expired ones. Kept as its own repository method (not folded into the
    // per-user find/save flow) for the same reason deleteByExpiresAtBefore
    // below is separate from this one: BleTokenCleanupTask's service-wide
    // sweep and issueSeed's per-user replacement are two different callers
    // with two different scopes.
    void deleteByUserId(Long userId);

    // Service-wide sweep for BleTokenCleanupTask's @Scheduled job — without
    // this, seeds belonging to a user who never calls /scan/ble/seed again
    // would sit in the table forever.
    void deleteByExpiresAtBefore(Instant cutoff);
}
