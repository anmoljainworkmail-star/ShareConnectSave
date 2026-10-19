package com.shareconnectsave.rating.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Event Envelope (Event-Driven Architecture): wire shape owned by
// contracts/kafka/report.filed.schema.json. Report Service is the producer
// (T054, a later ticket in SPECS.md); this record is Rating Service's own
// deserialization target for the same contract.
//
// report_count_for_target is denormalised by Report Service into this event
// specifically so Rating Service (and Admin Service) can apply threshold
// logic without ever querying Report Service's MongoDB directly - Database
// per Service in practice, same _comment_threshold the schema file itself
// documents.
//
// reportedId is declared `format: "uuid"` in the schema but, same as every
// other id on this platform, is actually a stringified BIGINT - kept as
// String here and parsed to long by ReportFiledConsumer.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportFiledEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("report_id") String reportId,
        @JsonProperty("reporter_id") String reporterId,
        @JsonProperty("reported_id") String reportedId,
        @JsonProperty("reason") String reason,
        @JsonProperty("report_count_for_target") Long reportCountForTarget
) {
}
