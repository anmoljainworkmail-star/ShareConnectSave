package com.shareconnectsave.admin_service.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Outbox Pattern (relay side) — this is the ONLY class in admin-service
// allowed to inject KafkaTemplate. Business code (ReviewQueueServiceImpl) and
// the write side (AdminOutboxServiceImpl) both depend only on
// IOutboxService and have no idea Kafka exists; this class is where that
// abstraction finally meets the real message bus, up to 500ms after the
// reinstate action was durably recorded.
//
// Single Responsibility (SOLID-S): this class does not know what a
// "reinstate" is or what a trust score means — it only knows how to move
// documents from "publishedAt == null" to "publishedAt == now" by handing
// their already-serialized payload to Kafka. Same shape as
// report-service's ReportOutboxRelay, simplified: admin-service has no
// escalation/business-document fields riding alongside the outbox state (see
// AdminOutboxEntity's own "STANDALONE variant" comment), so this relay uses a
// plain full-document save() to mark a row published rather than
// ReportOutboxRelay's targeted MongoTemplate.updateFirst — there is no OTHER
// field on this document a concurrent write could ever clobber, because
// nothing else in this service ever touches an AdminOutboxEntity after it is
// created. Copying ReportOutboxRelay's targeted-update protection here would
// guard against a race that cannot occur on this document shape.
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminOutboxRelay {

    private static final int BATCH_SIZE = 50;

    private final AdminOutboxRepository adminOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // @Scheduled(fixedDelay = 500): fixedDelay (not fixedRate) so the next
    // tick is measured from when this run FINISHES, not when it started —
    // same reasoning as every other outbox relay in this codebase. No
    // @Transactional: this project's dev Mongo instance is a single
    // standalone node with no multi-document transaction support, and each
    // row below is relayed and marked published independently.
    @Scheduled(fixedDelay = 500)
    public void relayPendingEvents() {
        List<AdminOutboxEntity> pendingEvents = adminOutboxRepository
                .findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));

        for (AdminOutboxEntity outboxEntity : pendingEvents) {
            publishOne(outboxEntity);
        }
    }

    // Resilience, one row at a time: a single event that fails to publish
    // must not stop the rest of this tick's batch. Leaving publishedAt null
    // is the entire retry mechanism across ticks — no retry-count/DLQ
    // bookkeeping here, matching rating-service's/connection-service's
    // simpler (non-report-service) OutboxRelay precedent, since this is a
    // low-volume, admin-triggered event with no acceptance criterion
    // demanding a DLQ.
    private void publishOne(AdminOutboxEntity outboxEntity) {
        try {
            // .get() makes the send synchronous within this tick: without
            // it, kafkaTemplate.send(...) returns an in-flight Future
            // immediately and this code would mark the row published before
            // Kafka ever acknowledged receipt — reintroducing exactly the
            // "DB says done, but the message never actually arrived" gap the
            // Outbox Pattern exists to close.
            kafkaTemplate.send(outboxEntity.getTopic(), outboxEntity.getPartitionKey(), outboxEntity.getPayload())
                    .get();

            // toBuilder() (not a setter — AdminOutboxEntity stays
            // @Getter-only/immutable, same style as every other @Document in
            // this codebase): builds a new instance with only publishedAt
            // changed, then re-saves it under the same id, which Spring Data
            // Mongo treats as a full-document replace keyed by _id.
            AdminOutboxEntity published = outboxEntity.toBuilder().publishedAt(Instant.now()).build();
            adminOutboxRepository.save(published);
        } catch (Exception e) {
            log.error("Failed to relay outbox event {} (topic={}) to Kafka — leaving unpublished for the next tick",
                    outboxEntity.getId(), outboxEntity.getTopic(), e);
        }
    }
}
