package com.shareconnectsave.discovery.ble;

import com.shareconnectsave.discovery.ble.domain.BleToken;
import org.springframework.data.jpa.repository.JpaRepository;

// Pattern: Repository (GoF/DDD) — plain collection-like access, same shape
// as ScanSessionRepository. No query methods yet: T024 (BLE token
// generation/resolution) is the ticket that will need "find by token_hash"
// and adds it there, not speculatively here.
public interface BleTokenRepository extends JpaRepository<BleToken, Long> {
}
