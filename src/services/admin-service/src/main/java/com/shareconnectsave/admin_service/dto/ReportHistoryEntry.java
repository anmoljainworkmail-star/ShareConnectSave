package com.shareconnectsave.admin_service.dto;

import java.time.Instant;

// One row of UserDetailResponse.reportHistory — admin-service's OWN outward
// shape, deliberately distinct from ReportServiceClient.ReportHistoryItem
// (that record is Report Service's wire shape as this client happens to
// consume it; this one is what admin-service promises ITS OWN callers,
// matching the same "don't let an upstream's shape leak through as your own
// contract" discipline discovery-service's PublicUserProfileResponse vs
// UserCardResponse split uses).
//
// Known follow-up (ticket's own "Known follow-ups" section): Report
// Service never exposes reportCountAtEscalation on its wire DTOs, so an
// admin reading this list has no direct visibility into why Report Service
// ordered/escalated a user's reports the way it did — this list is whatever
// ordering Report Service handed back at read time, not a real-time,
// exact guarantee.
public record ReportHistoryEntry(String id, String reporterId, String reason, String status, Instant createdAt) {
}
