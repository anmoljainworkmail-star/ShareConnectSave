package com.shareconnectsave.admin_service.dto;

import java.time.Instant;

// POST /admin/users/{id}/suspend's response — the mirror image of
// ReinstateResponse (see that record's own comment for the shape this one
// borrows), with one addition: expiredConnectionCount, so the acting admin
// can see at a glance that step 2 of the orchestration (the Connection
// Service call) actually found and closed something, not merely that the
// endpoint returned 200.
public record SuspendResponse(String userId, String status, int expiredConnectionCount, Instant updatedAt) {
}
