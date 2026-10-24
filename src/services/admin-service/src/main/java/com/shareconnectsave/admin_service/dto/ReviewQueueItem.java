package com.shareconnectsave.admin_service.dto;

import java.time.Instant;

// GET /admin/queue's response shape — per T057's own refined spec, a
// DISTINCT, cross-service aggregation view from Report Service's own GET
// /reports/queue (T053's escalated-reports-only shape). trustScore is the
// whole reason this is a separate concept: Report Service has no access to
// it at all, since trust score is Rating Service's data, never Report
// Service's (Database per Service).
//
// Field names are plain camelCase (userId, displayName, ...), matching this
// ticket's own JSON example verbatim — a deliberate departure from the
// snake_case convention every other wire DTO in this codebase uses, because
// the ticket's "Refined technical specification" spells this exact shape out
// literally rather than deferring to contracts/openapi/admin-service.yaml's
// (older, narrower) ReviewQueueItem schema. See ReviewQueueServiceImpl's
// class comment for the full flagged contract-vs-ticket conflict.
//
// trustScoreUnavailable/reportCountUnavailable extend the ticket's literal
// JSON example by one field each, for the same reason the ticket's own
// "Partial-failure degradation" section insists on for the detail view: a
// Rating/Report Service outage degrading ONE suspended user's row must never
// silently render as "this user has a trust score of 0" or "0 reports" — a
// real, meaningfully bad number — as if it were authoritative data.
public record ReviewQueueItem(
        String userId,
        String displayName,
        String status,
        Double trustScore,
        boolean trustScoreUnavailable,
        Integer reportCount,
        boolean reportCountUnavailable,
        Instant suspendedAt) {
}
