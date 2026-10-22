package com.shareconnectsave.report_service.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shareconnectsave.report_service.model.ReportReason;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/report.filed.schema.json. Snake_case field names via
// per-field @JsonProperty — same convention as connection-service's
// ConnectionAcceptedEvent — because this project has no project-wide
// Jackson naming strategy; ObjectMapper.writeValueAsString(this) in
// ReportServiceImpl therefore already produces the exact wire shape Admin
// Service (and eventually Rating Service's threshold-driven trust
// recalculation path) expects, with zero extra configuration.
//
// eventId == reportId (the SAME string, not two separate UUID.randomUUID()
// calls): a report is filed exactly once and published exactly once, so
// there is no second event that would ever need a distinct event_id —
// unlike connection-service's events, which reuse one connection_id across
// MULTIPLE distinct lifecycle events (requested, accepted, expired) and so
// need eventId to stay separate from the aggregate id.
//
// reason reuses ReportReason's own @JsonValue/@JsonCreator mapping — ONE
// enum, ONE source of truth, serialized identically on both this Kafka
// payload and the HTTP ReportResponse (see ReportReason's class comment).
//
// T053 — escalated is computed by THIS service (ReportServiceImpl, before
// this record is even constructed), not re-derived by a consumer. Folding a
// decision into the event itself — rather than shipping report_count_for_target
// alone and making every downstream consumer repeat "count >= threshold" with
// its own copy of the threshold — is what lets Admin Service's future queue
// aggregation (T057) trust escalated as a fact, the same way it already
// trusts reason or reported_id: Report Service made the call once, Admin
// Service never has to re-derive it (or risk disagreeing with it because its
// own copy of the threshold config drifted from this service's).
public record ReportFiledEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("report_id") String reportId,
        @JsonProperty("reporter_id") String reporterId,
        @JsonProperty("reported_id") String reportedId,
        @JsonProperty("reason") ReportReason reason,
        @JsonProperty("report_count_for_target") int reportCountForTarget,
        @JsonProperty("escalated") Boolean escalated
) {
}
