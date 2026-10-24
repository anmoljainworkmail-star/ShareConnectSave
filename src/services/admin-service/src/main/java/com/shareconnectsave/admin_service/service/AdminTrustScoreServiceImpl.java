package com.shareconnectsave.admin_service.service;

import org.springframework.stereotype.Service;

import com.shareconnectsave.admin_service.audit.TrustScoreOverrideAuditEntry;
import com.shareconnectsave.admin_service.audit.TrustScoreOverrideAuditLog;
import com.shareconnectsave.admin_service.client.RatingServiceClient;
import com.shareconnectsave.admin_service.client.RatingServiceClient.TrustScoreOverrideRequest;
import com.shareconnectsave.admin_service.client.RatingServiceClient.TrustScoreOverrideResult;
import com.shareconnectsave.admin_service.dto.TrustScoreOverrideDto;
import com.shareconnectsave.admin_service.dto.TrustScoreOverrideResponse;

import lombok.RequiredArgsConstructor;

// Single Responsibility (SOLID-S), per this ticket's own "Patterns
// demonstrated": this class does exactly two things — forward the override
// to Rating Service, and record the audit entry — and deliberately nothing
// else. It never computes, validates, or second-guesses the trust score
// formula itself (that stays Rating Service's TrustScoreCalculator's sole
// job); this class exists only to orchestrate the forward-and-log sequence.
//
// Dependency Inversion (SOLID-D): both collaborators are interfaces
// (RatingServiceClient) or a thin component (TrustScoreOverrideAuditLog),
// never a concrete WebClient or a hand-rolled static list — same shape
// ReviewQueueServiceImpl's own class comment documents for this service.
@Service
@RequiredArgsConstructor
public class AdminTrustScoreServiceImpl implements AdminTrustScoreService {

    private final RatingServiceClient ratingServiceClient;
    private final TrustScoreOverrideAuditLog auditLog;

    @Override
    public TrustScoreOverrideResponse overrideTrustScore(String adminUserId, String targetUserId,
                                                           TrustScoreOverrideDto dto) {
        // Audit logging as its own concern (per this ticket's own "Patterns
        // demonstrated"): recorded as an explicit, separate step BEFORE the
        // Rating Service call, not folded silently into the same statement.
        // Writing the audit entry first (rather than after) means the
        // admin's intent is captured even if the downstream forward below
        // were ever to fail — an audit trail that only exists on the happy
        // path would defeat the entire point of a human-accountability log.
        TrustScoreOverrideAuditEntry auditEntry = auditLog.record(
                adminUserId, targetUserId, dto.score(), dto.reason());

        TrustScoreOverrideResult result = ratingServiceClient.overrideTrustScore(
                targetUserId, new TrustScoreOverrideRequest(dto.score(), dto.reason()));

        return new TrustScoreOverrideResponse(
                result.userId(),
                result.score(),
                result.reason(),
                auditEntry.id(),
                adminUserId,
                auditEntry.timestamp());
    }
}
