package com.shareconnectsave.admin_service.audit;

import java.math.BigDecimal;
import java.time.Instant;

// One row of TrustScoreOverrideAuditLog. A plain record, not a JPA @Entity —
// per this ticket's own scope, the store behind it is in-memory for now (see
// TrustScoreOverrideAuditLog's class comment), so there is no persistence
// shape to distinguish from a DTO yet. When a future ticket moves this to a
// real table, THAT class takes the JPA `Entity` suffix per CLAUDE.md's Java
// Rules (forward-only — this record is not renamed retroactively here).
public record TrustScoreOverrideAuditEntry(
        String id,
        String adminUserId,
        String targetUserId,
        BigDecimal newScore,
        String reason,
        Instant timestamp) {
}
