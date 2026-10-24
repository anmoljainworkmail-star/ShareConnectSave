package com.shareconnectsave.admin_service.dto;

import java.time.Instant;

// POST /admin/users/{id}/reinstate's response — loosely mirrors
// contracts/openapi/admin-service.yaml's AccountStatusResult shape
// (user_id/status/updated_at), reusing the same three-field idea even though
// that schema was written against a PUT verb (see ReviewQueueController's own
// comment on the verb conflict this ticket resolves in favor of SPECS.md/the
// approved ticket text, both of which say POST).
public record ReinstateResponse(String userId, String status, Instant updatedAt) {
}
