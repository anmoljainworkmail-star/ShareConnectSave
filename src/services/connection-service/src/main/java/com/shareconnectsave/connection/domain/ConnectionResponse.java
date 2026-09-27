package com.shareconnectsave.connection.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

// Returned by GET /connections/pending and GET /connections/active.
// Field names match ConnectionRequest's own property names exactly
// (id, requesterId, recipientId, status, createdAt, expiresAt) on purpose —
// ConnectionMapper below relies on MapStruct's implicit same-name mapping
// and needs no @Mapping overrides for this direction.
public record ConnectionResponse(
        Long id,
        @JsonProperty("requester_id") Long requesterId,
        @JsonProperty("recipient_id") Long recipientId,
        ConnectionStatus status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("expires_at") Instant expiresAt
) {
}
