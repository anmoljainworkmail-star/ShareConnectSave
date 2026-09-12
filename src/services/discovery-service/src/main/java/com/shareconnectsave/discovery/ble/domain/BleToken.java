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

// Maps onto the ble_tokens table from V001__discovery_service_initial_schema.sql
// (T006). Only the entity + repository are built here — issuing/rotating
// tokens (HMAC-SHA256, 5-min expiry) is T024's job; this ticket is schema
// only, per its explicit out-of-scope list.
@Entity
@Table(name = "ble_tokens")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Hash, never the raw rotating token: the raw token is what gets
    // broadcast over Bluetooth in the clear, so the DB must not hold
    // anything that lets a reader of this table reverse a broadcast token
    // back into a match (same reasoning as password hashing).
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
