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

    // Retry shape (T054): 3 total attempts, exponential backoff base 200ms
    // (200ms, then 400ms between attempts — no sleep after the last one).
    // Deliberately small/explicit constants rather than a config value or a
    // spring-retry annotation: see the class-level "What NOT to do" note on
    // this ticket for why a plain loop was chosen over @Retryable here.
    private static final int MAX_PUBLISH_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 200L;

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
    // Leaving outboxPublishedAt null is the entire retry mechanism across
    // TICKS (next 500ms scheduler run); publishWithRetry below adds a
    // second, short-lived retry loop WITHIN a single tick for the common
    // "blip lasted a few hundred ms" case, so that case doesn't even need
    // to wait for the next tick.
    private void publishOne(Report report) {
        boolean published = publishWithRetry(report);
        if (!published) {
            // Dead-lettered this tick: outboxPublishedAt stays null on
            // purpose (see publishWithRetry's dead-letter log branch) so
            // the normal next-tick query picks this same document back up
            // and tries again once Kafka recovers. Nothing else to do here.
            return;
        }

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
    }

    // Decorator (GoF), plain English: a decorator wraps an existing thing
    // with the exact same shape from the outside, adding behaviour around
    // it without changing what it is. Think of a phone case — the phone
    // still works exactly the same way (same buttons, same screen), it's
    // just wrapped in something that protects it from a drop it couldn't
    // survive on its own. Here, the "phone" is the single
    // kafkaTemplate.send(...).get() call; this method wraps that same call
    // with a few protected retries, but to everything else in this class
    // (publishOne, above) it still looks like "try to publish one report,
    // tell me if it worked" — same shape, more resilient underneath.
    //
    // Dead Letter Queue (DLQ), log-based variant, plain English: a DLQ is
    // the "we couldn't deliver this, here's a copy, come look" shelf at a
    // post office — when a delivery van (Kafka) fails to drop off a
    // package (this event) after a few honest attempts, someone sets it
    // aside on a labeled shelf instead of quietly losing it, so a human can
    // act on it later. A classic DLQ is a second Kafka topic; report-service
    // has no outbox-status field to flip and a second topic sharing the
    // same broker would usually fail for the identical reason Kafka is
    // unreachable in the first place — so the "shelf" here is a single,
    // greppable ERROR log line carrying the complete event, intended for a
    // human to read and manually replay, not a new piece of infrastructure.
    private boolean publishWithRetry(Report report) {
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            try {
                // .get() makes the send synchronous within this attempt:
                // without it, kafkaTemplate.send(...) returns an in-flight
                // Future immediately and this code would mark the document
                // published before Kafka ever acknowledged receipt —
                // reintroducing exactly the "DB says done, but the message
                // never actually arrived" gap the Outbox Pattern exists to
                // close. Partition key = reportedId (this event's "who does
                // this concern" key, per the ticket's own spec) — not
                // reportId — so every report filed against the same target
                // lands on one partition and a future consumer
                // counting/ordering reports per target can rely on that
                // ordering.
                kafkaTemplate.send(TOPIC_REPORT_FILED, report.getReportedId(), report.getEventPayload()).get();
                return true;
            } catch (Exception e) {
                lastFailure = e;
                boolean isLastAttempt = attempt == MAX_PUBLISH_ATTEMPTS;
                if (isLastAttempt) {
                    break;
                }

                // Exponential backoff (200ms, then 400ms): cheap specifically
                // because spring.threads.virtual.enabled is already on for
                // this service (T051) — parking a virtual thread here for a
                // few hundred ms costs nothing like blocking a platform
                // thread would. Only meant to survive a sub-second network
                // blip; anything longer-lived falls through to the
                // dead-letter log below and waits for the relay's normal
                // 500ms-tick self-healing once Kafka recovers.
                long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while backing off before retrying {} publish for report {} (reportedId={})",
                            TOPIC_REPORT_FILED, report.getId(), report.getReportedId(), interrupted);
                    return false;
                }
            }
        }

        // All MAX_PUBLISH_ATTEMPTS attempts failed this tick: this is the
        // "dead letter" per T054's spec — not a second Kafka topic, not a
        // status field (Report has neither), just a tagged ERROR log
        // carrying the whole event so a human can find it and replay it by
        // hand. outboxPublishedAt is left null by the caller, so this same
        // document is still eligible for the next tick's query — a real
        // Kafka outage self-heals on its own once Kafka comes back, with no
        // extra bookkeeping here.
        log.error("DEAD_LETTER_REPORT_FILED reportId={} reportedId={} attempts={} eventPayload={}",
                report.getId(), report.getReportedId(), MAX_PUBLISH_ATTEMPTS, report.getEventPayload(), lastFailure);
        return false;
    }
}
