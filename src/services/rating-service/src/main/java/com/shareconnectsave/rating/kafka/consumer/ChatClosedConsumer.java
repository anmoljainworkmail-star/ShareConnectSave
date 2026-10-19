package com.shareconnectsave.rating.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.rating.chat.ClosedChat;
import com.shareconnectsave.rating.chat.ClosedChatRepository;
import com.shareconnectsave.rating.kafka.ProcessedEventRepository;
import com.shareconnectsave.rating.kafka.domain.ProcessedEvent;
import com.shareconnectsave.rating.kafka.event.ChatClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Observer (via events) - Chat Service publishes chat.closed once,
// with no idea Rating Service exists. Discovery Service (T059) and
// Connection Service (T062) also react to the same fact independently - none
// of the three subscribers coordinate with each other or with Chat Service.
//
// Single Responsibility (SOLID-S): this class does exactly one thing - keep
// closed_chats warm so ClosedChatBackedChatClosureChecker has something real
// to answer "is this connection's chat closed?" with. It never validates a
// rating submission itself (RatingServiceImpl.submitRating's guard clause
// does that) and never touches this service's own outbox table - it is a
// consumer, not a producer.
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatClosedConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ClosedChatRepository closedChatRepository;

    // groupId "rating-service": one consumer group per SERVICE, not per
    // listener class - same convention connection-service's
    // TrustScoreUpdatedConsumer documents, shared by all three of this
    // ticket's consumers.
    @KafkaListener(topics = "chat.closed", groupId = "rating-service")
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
            // Log and skip, never throw: a thrown exception here would block
            // this partition's offset commit, and Kafka would redeliver this
            // exact unparseable message forever (a "poison pill").
            log.error("Failed to deserialize chat.closed message, skipping: {}", ex.getMessage());
            return;
        }

        // Guard clause: reject messages missing fields this consumer cannot
        // function without, before using any of them - the same shape
        // connection-service's TrustScoreUpdatedConsumer uses.
        if (event.eventId() == null || event.connectionId() == null
                || event.userAId() == null || event.userBId() == null) {
            log.error("Received chat.closed event with a missing required field, skipping: {}", event);
            return;
        }

        // Idempotency (kafka-outbox skill): checked BEFORE any work, so a
        // redelivered message is a no-op read instead of a second insert
        // attempt.
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping already-processed chat.closed event {}", event.eventId());
            return;
        }

        long connectionId;
        long userAId;
        long userBId;
        try {
            connectionId = Long.parseLong(event.connectionId());
            userAId = Long.parseLong(event.userAId());
            userBId = Long.parseLong(event.userBId());
        } catch (NumberFormatException ex) {
            log.error("Received chat.closed event {} with a non-numeric id, skipping", event.eventId());
            return;
        }

        // Defense in depth alongside the processed_events check above: guards
        // against a second closed_chats row for the same connection even in
        // the narrow window where only the event_id changed (e.g. a manual
        // replay) - same "guard clause in front of a unique index" shape
        // RatingServiceImpl.submitRating already uses for duplicate ratings.
        if (!closedChatRepository.existsByConnectionId(connectionId)) {
            closedChatRepository.save(ClosedChat.builder()
                    .connectionId(connectionId)
                    .userAId(userAId)
                    .userBId(userBId)
                    .build());
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .build());
    }
}
