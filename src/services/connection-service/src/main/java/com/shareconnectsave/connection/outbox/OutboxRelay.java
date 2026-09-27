package com.shareconnectsave.connection.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// Outbox Pattern (relay side) - this is the ONLY class in connection-service
// allowed to inject KafkaTemplate. Business code (ConnectionServiceImpl) and
// the write side (OutboxServiceImpl) both depend only on IOutboxService and
// have no idea Kafka exists; this class is where that abstraction finally
// meets the real message bus, exactly 500ms after the fact was durably
// recorded in the outbox table.
//
// Single Responsibility (SOLID-S): this class does not know what a
// "connection" is, what ACCEPTED vs EXPIRED means, or any state-transition
// rule - it only knows how to move rows from PENDING to PROCESSED by handing
// their payload to Kafka. That split mirrors ConnectionExpiryScheduler
// (WHEN to check) vs ConnectionServiceImpl (WHAT overdue means) elsewhere in
// this service.
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // @Scheduled(fixedDelay = 500): fixedDelay (not fixedRate) so the NEXT
    // tick is measured from when this run FINISHES, not when it started -
    // if a batch of sends is briefly slow, ticks don't pile up and start
    // overlapping each other. @Transactional wraps the whole batch: the
    // per-row status flip to PROCESSED (via markProcessed) is only visible
    // to other readers once this method returns successfully, matching the
    // same "read/act atomically" discipline used everywhere else in this
    // service.
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository
                .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent outboxEvent : pendingEvents) {
            publishOne(outboxEvent);
        }
    }

    // Resilience, one row at a time: same discipline as
    // ConnectionExpiryScheduler's per-id try/catch - a single row that fails
    // to publish (Kafka momentarily unreachable, broker rejects it, etc.)
    // must not stop the rest of this tick's batch from going out, and must
    // not throw out of the @Transactional method (which would roll back
    // every row this tick already marked PROCESSED). Leaving a failed row
    // PENDING is the entire retry mechanism - no retry-count/DLQ bookkeeping
    // here by design (explicitly out of scope for T032; see the ticket's
    // "what NOT to do" section - DLQ handling belongs to Report Service).
    private void publishOne(OutboxEvent outboxEvent) {
        try {
            // .get() makes the send synchronous within this tick: without
            // it, kafkaTemplate.send(...) returns a in-flight Future
            // immediately and this code would mark the row PROCESSED before
            // Kafka ever acknowledged receipt - reintroducing exactly the
            // "DB says done, but the message never actually arrived" gap
            // the Outbox Pattern exists to close.
            kafkaTemplate.send(outboxEvent.getTopic(), outboxEvent.getPartitionKey(), outboxEvent.getPayload())
                    .get();

            outboxEvent.markProcessed(Instant.now());
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to relay outbox event {} (topic={}) to Kafka - leaving PENDING for the next tick",
                    outboxEvent.getId(), outboxEvent.getTopic(), e);
        }
    }
}
