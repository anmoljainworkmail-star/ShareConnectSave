package com.shareconnectsave.connection.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.connection.cache.IRequestLimitCache;
import com.shareconnectsave.connection.kafka.ProcessedEventsRepository;
import com.shareconnectsave.connection.kafka.domain.ProcessedEvent;
import com.shareconnectsave.connection.kafka.event.TrustScoreUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Observer (via events) — Rating Service (Phase 6, not yet built)
// publishes a recalculated trust score once, with no idea Connection
// Service exists. This consumer and Discovery Service's own
// TrustScoreUpdatedEventListener (T025) both react independently to the
// same "trust.score.updated" fact; neither subscriber needs to know the
// other is listening, and Rating Service needs to know about neither.
//
// Single Responsibility (SOLID-S): this class does exactly one thing — keep
// IRequestLimitCache warm. It never enforces the limit itself (that guard
// clause lives in ConnectionServiceImpl.createConnection, unchanged by this
// ticket) and never touches this service's own outbox table — it is a
// consumer, not a producer, so IOutboxService has no reason to appear
// anywhere in this class.
@Component
@RequiredArgsConstructor
@Slf4j
public class TrustScoreUpdatedConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEventsRepository processedEventsRepository;
    private final IRequestLimitCache requestLimitCache;

    // groupId "connection-service": one consumer group per SERVICE (not per
    // listener class) is this platform's convention — see
    // discovery-service's TrustScoreUpdatedEventListener/UserVerifiedEventListener,
    // which share "discovery-service" the same way. Kafka scopes a consumer
    // group to topic-partition assignment, so this being Connection
    // Service's first-ever consumer changes nothing about that convention.
    @KafkaListener(topics = "trust.score.updated", groupId = "connection-service")
    @Transactional
    public void onTrustScoreUpdated(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value trust.score.updated message, skipping");
            return;
        }

        TrustScoreUpdatedEvent event;
        try {
            event = objectMapper.readValue(rawMessage, TrustScoreUpdatedEvent.class);
        } catch (JsonProcessingException ex) {
            // Log and skip, never throw: a thrown exception here would block
            // this partition's offset commit, and Kafka would redeliver this
            // exact unparseable message forever (a "poison pill"). Only the
            // exception message is logged, never the raw payload — future
            // event fields could carry sensitive data that should never land
            // in application logs.
            log.error("Failed to deserialize trust.score.updated message, skipping: {}", ex.getMessage());
            return;
        }

        // Guard clause: reject messages missing the fields this consumer
        // cannot function without, before using any of them. A null eventId
        // would NPE on the idempotency check below; a null userId/requestLimit
        // would NPE on the cache update — both would stall this partition
        // with an unrecoverable poison pill instead of skipping one bad
        // message.
        if (event.eventId() == null || event.userId() == null || event.requestLimit() == null) {
            log.error("Received trust.score.updated event with a missing required field, skipping: {}", event);
            return;
        }

        String eventId = event.eventId().toString();

        // Idempotency (kafka-outbox skill): Kafka's at-least-once delivery
        // means this exact event_id can arrive more than once. Checked
        // BEFORE the cache write, not after — the whole point of this guard
        // is to make a redelivered message a no-op read instead of a second
        // write.
        if (processedEventsRepository.existsById(eventId)) {
            log.info("Skipping already-processed trust.score.updated event {}", eventId);
            return;
        }

        // userId on the wire is a stringified BIGINT, never a real UUID —
        // see TrustScoreUpdatedEvent.userId's own comment. A malformed value
        // here is still a poison-pill risk, so it gets the same log-and-skip
        // treatment as a JSON parse failure rather than an uncaught
        // NumberFormatException.
        long userId;
        try {
            userId = Long.parseLong(event.userId());
        } catch (NumberFormatException ex) {
            log.error("Received trust.score.updated event {} with a non-numeric user_id, skipping: {}",
                    eventId, event.userId());
            return;
        }

        // Cache-Aside's event-driven cousin: this is the WRITE side of a
        // cache kept warm by an event feed rather than populated lazily on a
        // read miss (contrast Discovery Service's Redis profile cache,
        // populated on-demand the first time a profile is requested).
        // ConnectionServiceImpl.createConnection's getLimit() call is the
        // read side this write keeps current — that method is deliberately
        // left untouched by this ticket.
        requestLimitCache.update(userId, event.requestLimit());

        processedEventsRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .build());
    }
}
