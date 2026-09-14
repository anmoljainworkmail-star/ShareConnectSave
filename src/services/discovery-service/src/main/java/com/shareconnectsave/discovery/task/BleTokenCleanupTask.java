package com.shareconnectsave.discovery.task;

import com.shareconnectsave.discovery.ble.BleSeedRepository;
import com.shareconnectsave.discovery.config.BleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// Pattern: scheduled sweep, same shape as the kafka-outbox skill's own
// pruneOldEvents example — a background job that periodically deletes rows
// a TTL concept has already logically expired, since ble_seeds (like v1's
// ble_tokens) has no native database-level TTL/expiry mechanism (SQL
// Server, unlike MongoDB, has no TTL index). Without this, seeds belonging
// to a user who stops requesting new ones (see BleTokenServiceImpl.issueSeed's
// own per-user delete, which only fires on that SAME user's next request)
// would sit in the table forever.
//
// v2 (T024 rework): sweeps ble_seeds via BleSeedRepository now, instead of
// ble_tokens via BleTokenRepository — SchedulingConfig/@EnableScheduling is
// untouched, nothing about scheduling itself changes.
@Component
@Slf4j
@RequiredArgsConstructor
public class BleTokenCleanupTask {

    private final BleSeedRepository bleSeedRepository;
    private final BleProperties bleProperties;

    // fixedDelayString (not fixedDelay) so the interval is read from
    // application.yml/an env var (No Hardcoded Config) rather than a literal
    // baked into the annotation. @EnableScheduling (SchedulingConfig) is what
    // makes Spring honor this annotation at all.
    @Scheduled(fixedDelayString = "${ble.cleanup.interval-ms}")
    @Transactional
    public void purgeExpiredTokens() {
        Instant now = Instant.now();
        bleSeedRepository.deleteByExpiresAtBefore(now);
        log.debug("Purged expired BLE seeds older than {}", now);
    }
}
