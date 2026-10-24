package com.shareconnectsave.admin_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

// PATCH /admin/users/{id}/trust-score's response — per the ticket's own
// spec, "the updated trust score payload as echoed back by Rating Service
// ... plus ... the audit entry's id" (Tech Lead's choice: a single flattened
// response, not two separate top-level objects, same "one simple front door"
// shape ReviewQueueServiceImpl's own Facade comment describes for the GET
// side of this service).
public record TrustScoreOverrideResponse(
        String userId,
        BigDecimal score,
        String reason,

        // The audit entry's id (see TrustScoreOverrideAuditLog) — lets the
        // calling admin UI deep-link back to this exact override later,
        // without this endpoint needing a second round trip to look it up.
        String auditId,
        String overriddenBy,
        Instant overriddenAt) {
}
