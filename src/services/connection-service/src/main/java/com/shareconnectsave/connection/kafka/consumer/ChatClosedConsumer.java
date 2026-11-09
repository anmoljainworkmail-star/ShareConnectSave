package com.shareconnectsave.connection.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.connection.ConnectionService;
import com.shareconnectsave.connection.kafka.ProcessedEventsRepository;
import com.shareconnectsave.connection.kafka.domain.ProcessedEvent;
import com.shareconnectsave.connection.kafka.event.ChatClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Saga (Choreography), the closing step of ConnectionLifecycleSaga:
//   accept -> open chat -> met/timeout -> chat.closed -> THIS consumer ends it.
//
// The Problem: Chat Service owns the chat room and knows when the conversation
// is over. Connection Service owns the connection record and decides whether a
// user "has an active connection". Before this class, Chat Service said "closed"
// and Connection Service never heard it, so the connection stayed ACCEPTED
// forever: the pair looked permanently connected and neither could send another
// request.
//
// Why it matters: in a database-per-service system, one service cannot reach
// into another's table to flip a flag. The only way two services stay
// consistent is that the owner publishes a fact and the other reacts.
//
// How this solves it: Chat Service publishes chat.closed once, not knowing who
// listens (Observer via events). This consumer reacts by closing Connection
// Service's own records. Rating Service independently reacts to the same event
// to prompt the pair for ratings - neither subscriber knows about the other.
//
// Example from this project: connection 12 (20003 and 30004) met, the 5-minute
// grace period passed, Chat Service published chat.closed, and this consumer
// moves connection 12 from ACCEPTED to COMPLETED so both users are free again.
//
// Single Responsibility (SOLID-S): this class is Kafka mechanics only - parse,
// guard, dedupe, hand off. It does not know what "completing a connection" means;
// that rule lives in ConnectionService.completeConnection (Dependency Inversion,
// SOLID-D: the consumer depends on the interface, not ConnectionServiceImpl).
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatClosedConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEventsRepository processedEventsRepository;
    private final ConnectionService connectionService;

    // Same group id as TrustScoreUpdatedConsumer: one consumer group per SERVICE,
    // not per listener (platform convention; see application.yml).
    //
    // @Transactional on the consumer, with completeConnection joining it:
    // the connection update, the saga update and the processed_events row all
    // commit together or not at all. That atomicity is what makes redelivery
    // safe: a crash mid-way leaves no half-applied state and no ledger row
    // claiming work that was rolled back.
    @KafkaListener(topics = "chat.closed", groupId = "connection-service")
    @Transactional
    public void onChatClosed(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value chat.closed message, skipping");
            return;
        }

        ChatClosedEvent event;
        try {
            event = objectMapper.readValue(rawMessage, ChatClosedEvent.class);
        } catch (JsonProcessingException ex) {
            // Log and skip, never throw: a thrown exception means "do not commit
            // the offset", so Kafka would redeliver this unparseable message
            // forever (a poison pill) and block the partition behind it. Only the
            // exception message is logged, never the payload.
            log.error("Failed to deserialize chat.closed message, skipping: {}", ex.getMessage());
            return;
        }

        // Guard clause: both fields are needed before anything else. A null
        // eventId would NPE on the idempotency check, a null connectionId on the
        // parse below - each would become a poison pill instead of one skipped
        // message.
        if (event.eventId() == null || event.connectionId() == null) {
            log.error("Received chat.closed event with a missing required field, skipping: {}", event);
            return;
        }

        String eventId = event.eventId().toString();

        // Idempotency (kafka-outbox skill): Kafka delivers at-least-once, so this
        // same event_id can arrive twice. Checked BEFORE acting so a duplicate is
        // a harmless read instead of a second completion attempt.
        if (processedEventsRepository.existsById(eventId)) {
            log.info("Skipping already-processed chat.closed event {}", eventId);
            return;
        }

        long connectionId;
        try {
            connectionId = Long.parseLong(event.connectionId());
        } catch (NumberFormatException ex) {
            // Same poison-pill reasoning as the JSON parse failure above.
            log.error("Received chat.closed event {} with a non-numeric connection_id, skipping: {}",
                    eventId, event.connectionId());
            return;
        }

        connectionService.completeConnection(connectionId);
        log.info("Handled chat.closed event {} for connection {} (reason: {})",
                eventId, connectionId, event.closedReason());

        // Recorded LAST, inside the same transaction as the work above: the ledger
        // row exists if and only if the completion committed.
        processedEventsRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .build());
    }
}
