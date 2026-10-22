package com.shareconnectsave.report_service.kafka;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shareconnectsave.report_service.model.Report;
import com.shareconnectsave.report_service.repository.ReportRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Outbox Pattern (relay side), Mongo-adapted — this is the ONLY class in
// report-service allowed to inject KafkaTemplate. Business code
// (ReportServiceImpl) has no idea Kafka exists; it only ever writes
// eventPayload/outboxPublishedAt onto the Report document it already saves.
// This class is where that abstraction finally meets the real message bus,
// up to 500ms after the report was durably recorded. Same shape as
// connection-service's OutboxRelay (T032) and rating-service's (T042),
// adapted for a MongoRepository instead of a JpaRepository/outbox table —
// see Report's own class comment for why Mongo-only services fold the
// outbox fields onto the business document instead of a separate table.
//
// Single Responsibility (SOLID-S): this class does not know what a
// "report" is, what a reason or a status means, or how report_count_for_target
// was computed — it only knows how to move documents from
// "outboxPublishedAt == null" to "outboxPublishedAt == now" by handing their
// already-serialized payload to Kafka.
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportOutboxRelay {

    private static final int BATCH_SIZE = 50;
    private static final String TOPIC_REPORT_FILED = "report.filed";

    private final ReportRepository reportRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MongoTemplate mongoTemplate;

    // @Scheduled(fixedDelay = 500): fixedDelay (not fixedRate) so the NEXT
    // tick is measured from when this run FINISHES, not when it started —
    // same reasoning as connection-service's OutboxRelay. No
    // @Transactional here: unlike the SQL-backed relays, there is no
    // multi-row transaction to wrap — this project's dev Mongo instance is
    // a single standalone node with no multi-document transaction support
    // anyway, and each row below is relayed and marked published
    // independently via its own targeted single-document update.
    @Scheduled(fixedDelay = 500)
    public void relayPendingEvents() {
        List<Report> pendingReports = reportRepository
                .findByOutboxPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));

        for (Report report : pendingReports) {
            publishOne(report);
        }
    }

    // Resilience, one row at a time: same discipline as
    // connection-service's OutboxRelay.publishOne — a single report that
    // fails to publish (Kafka momentarily unreachable, broker rejects it,
    // etc.) must not stop the rest of this tick's batch from going out.
    // Leaving outboxPublishedAt null is the entire retry mechanism — no
    // retry-count/DLQ bookkeeping here by design (that is T054's scope, not
    // this ticket's).
    private void publishOne(Report report) {
        try {
            // .get() makes the send synchronous within this tick: without
            // it, kafkaTemplate.send(...) returns an in-flight Future
            // immediately and this code would mark the document published
            // before Kafka ever acknowledged receipt — reintroducing exactly
            // the "DB says done, but the message never actually arrived"
            // gap the Outbox Pattern exists to close. Partition key =
            // reportedId (this event's "who does this concern" key, per the
            // ticket's own spec) — not reportId — so every report filed
            // against the same target lands on one partition and a future
            // consumer counting/ordering reports per target can rely on
            // that ordering.
            kafkaTemplate.send(TOPIC_REPORT_FILED, report.getReportedId(), report.getEventPayload()).get();

            // Spring Data MongoDB technique beyond T051's plain
            // MongoRepository usage: MongoTemplate.updateFirst issues a
            // targeted, single-FIELD update (a Mongo $set on
            // outboxPublishedAt only), not a full-document re-save via
            // reportRepository.save(report). Report.save() would overwrite
            // every field on the document with whatever this in-memory
            // `report` object currently holds — if a viewer-visible field
            // (e.g. a future admin PATCH to `details`) changed concurrently
            // in Mongo between this relay's read and this write, a full
            // re-save would silently clobber that change. updateFirst only
            // ever touches the one field this relay actually owns, so a
            // concurrent read (or write) of any other field on the same
            // document is never affected.
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("id").is(report.getId())),
                    Update.update("outboxPublishedAt", Instant.now()),
                    Report.class
            );
        } catch (Exception e) {
            log.error("Failed to relay {} event for report {} (reportedId={}) to Kafka - leaving unpublished for the next tick",
                    TOPIC_REPORT_FILED, report.getId(), report.getReportedId(), e);
        }
    }
}
