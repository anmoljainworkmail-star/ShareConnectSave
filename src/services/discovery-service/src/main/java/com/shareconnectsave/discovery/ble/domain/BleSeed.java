package com.shareconnectsave.discovery.ble.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the ble_seeds table from V002__ble_seeds.sql, which replaces
// v1's ble_tokens/BleToken. Rolling identifiers derived from a locally-held
// secret (the actual concept behind Apple/Google's Exposure Notification
// system): unlike BleToken's tokenHash, this column holds the raw seed
// itself, never a hash of it — BleTokenServiceImpl.resolveTokens must be
// able to recompute HMAC-SHA256(seed, window) to check a submitted
// broadcast value, which a one-way hash would make impossible. See this
// ticket's Security note for why that larger blast radius is an accepted
// trade-off, not an oversight.
@Entity
@Table(name = "ble_seeds")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // The raw seed, Base64url-encoded — see BleTokenServiceImpl.issueSeed
    // for generation and BleTokenServiceImpl's derive method for how it is
    // decoded back to raw bytes to use as the per-window HMAC key. Never
    // logged, never returned from any endpoint other than the one-time
    // /scan/ble/seed registration response.
    @Column(name = "seed", nullable = false)
    private String seed;

    // insertable/updatable = false: same reasoning as ScanSession.startedAt
    // — the DDL's own SYSUTCDATETIME() default is the DB's ground truth for
    // "when was this row actually committed", not the app server's clock.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
