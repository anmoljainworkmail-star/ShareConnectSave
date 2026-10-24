package com.shareconnectsave.admin_service.outbox;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

// Outbox Pattern — write side only, Mongo-adapted. Single Responsibility
// (SOLID-S), the mirror image of AdminOutboxRelay's own "only publishes, no
// business logic" discipline: this class only ever builds and saves ONE
// AdminOutboxEntity document, it never touches KafkaTemplate.
//
// No transaction annotation here, and that absence is deliberate (contrast
// with rating-service's OutboxServiceImpl, which relies on its CALLER's
// already-open SQL @Transactional): this project's dev Mongo instance is a
// single standalone node with no multi-document transaction support anyway,
// and — unlike rating-service's submitRating, which inserts a Rating row AND
// an OutboxEvent row that must commit together — ReviewQueueServiceImpl.reinstate
// has no second local write for this one to be atomic WITH. A single Mongo
// document save is already atomic on its own, so this one write IS the
// entire local "transaction" reinstate's spec describes.
@Service
@RequiredArgsConstructor
public class AdminOutboxServiceImpl implements IOutboxService {

    private final AdminOutboxRepository adminOutboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String topic, String partitionKey, String eventId, Object payload) {
        String json = serialize(topic, payload);

        AdminOutboxEntity outboxEntity = AdminOutboxEntity.builder()
                .id(eventId)
                .topic(topic)
                .partitionKey(partitionKey)
                .payload(json)
                .createdAt(Instant.now())
                .publishedAt(null)
                .build();

        adminOutboxRepository.save(outboxEntity);
    }

    private String serialize(String topic, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A payload that cannot be serialized is a programmer error (the
            // event record itself is malformed), not a transient failure
            // worth swallowing — fail loudly, same reasoning as
            // report-service's ReportServiceImpl.serialize and
            // rating-service's OutboxServiceImpl.serialize.
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + topic, e);
        }
    }
}
