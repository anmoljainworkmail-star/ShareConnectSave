package com.shareconnectsave.connection.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Outbox Pattern — write side only. This class's ONLY job is: serialize the
// payload to JSON and save a PENDING row in the same transaction the caller
// is already inside (Single Responsibility, SOLID-S — same discipline the
// ticket calls out for OutboxRelay/TrustScoreCalculator elsewhere in this
// project: "OutboxRelay only publishes — no business logic"; the mirror
// image applies here too — this class only writes the outbox row, it never
// touches KafkaTemplate).
//
// No @Transactional on publish() itself, and that absence is deliberate:
// this method must run inside whatever transaction its caller (e.g.
// ConnectionServiceImpl.acceptConnection, itself @Transactional) already
// opened, using Spring's default REQUIRED propagation. If this method
// opened its OWN transaction instead, the outbox row could commit
// independently of the connection_requests status update it's supposed to
// be atomic with — exactly the split-brain the Outbox Pattern exists to
// prevent.
//
// Follow-up (explicitly out of scope for T030): the scheduled relay that
// actually polls outbox for PENDING rows and calls
// KafkaTemplate.send(...) is a separate, later ticket (T032). Nothing in
// this file reads a PENDING row back out — this class only ever inserts.
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements IOutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String topic, Object payload) {
        String json = serialize(topic, payload);

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .topic(topic)
                .payload(json)
                .status(OutboxStatus.PENDING)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private String serialize(String topic, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A payload that cannot be serialized is a programmer error
            // (the event record itself is malformed), not a transient
            // failure worth swallowing or retrying — fail the whole
            // transaction loudly so the bug surfaces immediately instead of
            // silently dropping the outbox row.
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + topic, e);
        }
    }
}
