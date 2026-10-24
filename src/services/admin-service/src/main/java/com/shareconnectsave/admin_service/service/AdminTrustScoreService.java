package com.shareconnectsave.admin_service.service;

import com.shareconnectsave.admin_service.dto.TrustScoreOverrideDto;
import com.shareconnectsave.admin_service.dto.TrustScoreOverrideResponse;

// Dependency Inversion (SOLID-D): AdminTrustScoreController depends on this
// interface, never on AdminTrustScoreServiceImpl — same convention
// ReviewQueueService's own class comment documents for this service.
public interface AdminTrustScoreService {

    // adminUserId is the ACTING ADMIN (X-User-Id), targetUserId is the
    // account whose score is being overridden (the {id} path variable) —
    // same split ReviewQueueController's reinstate() already establishes
    // between "who is calling" and "who this call is about".
    TrustScoreOverrideResponse overrideTrustScore(String adminUserId, String targetUserId, TrustScoreOverrideDto dto);
}
