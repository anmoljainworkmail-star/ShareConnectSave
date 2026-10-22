package com.shareconnectsave.report_service.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Open/Closed (SOLID-O), same reasoning as ReportReason: the lifecycle a
 * report can be in is DATA, enum constants, not a free-text status column
 * validated by scattered if/else checks. Only T053's escalation logic (and
 * whatever future ticket builds the admin review queue) ever assigns
 * ESCALATED/REVIEWED/DISMISSED — this ticket only ever writes OPEN.
 *
 * Wire casing: contracts/openapi/report-service.yaml's Report.status enum
 * is lowercase ("open", "escalated", "reviewed", "dismissed"), unlike
 * ReportReason's Title Case strings. Unlike ReportReason, nothing ever
 * DESERIALIZES a ReportStatus from client input in this ticket (it is
 * response-only), so a lighter per-constant @JsonProperty is enough here —
 * there is no inbound wire value to validate, which is the only reason
 * ReportReason needs the heavier @JsonValue/@JsonCreator round-trip instead.
 */
public enum ReportStatus {
    @JsonProperty("open")
    OPEN,

    @JsonProperty("escalated")
    ESCALATED,

    @JsonProperty("reviewed")
    REVIEWED,

    @JsonProperty("dismissed")
    DISMISSED
}
