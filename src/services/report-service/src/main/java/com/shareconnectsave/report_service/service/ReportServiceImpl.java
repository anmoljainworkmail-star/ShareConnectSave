package com.shareconnectsave.report_service.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.report_service.dto.ReportCreateDto;
import com.shareconnectsave.report_service.dto.ReportResponse;
import com.shareconnectsave.report_service.exception.SelfReportException;
import com.shareconnectsave.report_service.kafka.event.ReportFiledEvent;
import com.shareconnectsave.report_service.mapper.ReportMapper;
import com.shareconnectsave.report_service.model.Report;
import com.shareconnectsave.report_service.model.ReportReason;
import com.shareconnectsave.report_service.model.ReportStatus;
import com.shareconnectsave.report_service.repository.ReportRepository;

import lombok.RequiredArgsConstructor;

// Single Responsibility (SOLID-S): every validation rule, count lookup, and
// event-payload decision for filing a report lives HERE — ReportController
// only translates HTTP <-> DTOs and never contains an if/else of its own.
//
// Dependency Inversion (SOLID-D): this class depends on ReportRepository (an
// abstraction over MongoRepository) and ReportMapper (MapStruct-generated),
// never on MongoTemplate or KafkaTemplate — this is exactly the boundary
// that keeps "what a report is" separate from "how it eventually reaches
// Kafka" (ReportOutboxRelay's job, not this class's).
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final String TOPIC_REPORT_FILED = "report.filed";

    private final ReportRepository reportRepository;
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    @Override
    public ReportResponse fileReport(String reporterId, ReportCreateDto dto) {
        // Guard clause, checked first and without touching the DB at all —
        // same "cheapest check first, before any I/O" ordering as
        // connection-service's self-connection guard in createConnection.
        if (reporterId.equals(dto.reportedId())) {
            throw new SelfReportException(reporterId);
        }

        // Guard clause: explicit conversion from wire String to enum,
        // BEFORE any Kafka event construction or DB I/O. This ensures an
        // invalid reason value throws InvalidReportReasonException as a
        // business rule (caught by ReportExceptionHandler.handleInvalidReportReason,
        // producing a clean 400), not wrapped in HttpMessageNotReadableException
        // when Jackson tries to deserialize ReportCreateDto.reason — same Guard
        // pattern rating-service's RatingServiceImpl uses for RatingTag.
        ReportReason reason = ReportReason.fromWireValue(dto.reason());

        // Counting report_count_for_target: a read-then-write, not an
        // atomic counter increment. Acceptable here for the same reason the
        // folded-outbox design on Report itself is acceptable — this
        // project's dev Mongo instance is a single standalone node, so it
        // cannot give a transactional atomic counter across concurrent
        // requests either way. Under a genuine race (two reports against the
        // same target landing in the same instant), the worst case is a
        // threshold-escalation check firing one event later than ideal — no
        // report is ever lost or corrupted. A distributed lock or
        // findAndModify-based atomic counter would be disproportionate to
        // that actual risk, and is explicitly out of scope for this ticket.
        List<Report> existingReportsAgainstTarget = reportRepository.findByReportedId(dto.reportedId());
        int reportCountForTarget = existingReportsAgainstTarget.size() + 1;

        // App-assigned id (UUID.randomUUID(), not Mongo-generated) — minted
        // BEFORE the document exists so it can be embedded in its own
        // eventPayload below. Reused as BOTH report_id and event_id in
        // ReportFiledEvent: see that record's own class comment for why one
        // report filed exactly once needs no separate event_id.
        String reportId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ReportFiledEvent event = new ReportFiledEvent(
                reportId,
                reportId,
                reporterId,
                dto.reportedId(),
                reason,
                reportCountForTarget
        );

        // Outbox Pattern, Mongo-adapted: build the COMPLETE Report document
        // — business fields AND the pending outbox fields
        // (eventPayload/outboxPublishedAt) — before the first save() call,
        // so persisting the report and recording "an event is now pending
        // for it" happen in the exact same single-document write. See
        // Report's own class comment for why that single write is what
        // makes this a valid Outbox Pattern implementation without a
        // multi-document Mongo transaction.
        Report report = Report.builder()
                .id(reportId)
                .reporterId(reporterId)
                .reportedId(dto.reportedId())
                .reason(reason)
                .connectionId(dto.connectionId())
                .details(dto.details())
                .status(ReportStatus.OPEN)
                .createdAt(now)
                .eventPayload(serialize(event))
                .outboxPublishedAt(null)
                .build();

        Report saved = reportRepository.save(report);

        return reportMapper.toResponse(saved);
    }

    private String serialize(ReportFiledEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // A payload that cannot be serialized is a programmer error
            // (the event record itself is malformed), not a transient
            // failure worth swallowing — fail the whole request loudly so
            // the bug surfaces immediately instead of silently persisting a
            // report that can never be relayed to Kafka. Same reasoning as
            // connection-service's OutboxServiceImpl.serialize.
            throw new IllegalStateException(
                    "Failed to serialize " + TOPIC_REPORT_FILED + " event payload", e);
        }
    }
}
