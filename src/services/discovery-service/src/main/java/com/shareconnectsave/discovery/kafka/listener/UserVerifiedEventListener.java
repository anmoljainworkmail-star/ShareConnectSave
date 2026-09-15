package com.shareconnectsave.discovery.kafka.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.discovery.cache.DiscoveryEligibilityCacheService;
import com.shareconnectsave.discovery.kafka.ProcessedEventsRepository;
import com.shareconnectsave.discovery.kafka.domain.ProcessedEvent;
import com.shareconnectsave.discovery.kafka.event.UserVerifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Pattern: Observer (via Kafka) — User Service publishes "a user finished
// onboarding" once, with no idea Discovery Service (or anyone else) is
// listening. This class is the subscriber half: it reacts autonomously, on
// its own consumer group, without User Service ever calling it directly.
// Same shape as the kafka-outbox skill's connection.accepted example.
//
// Payload is consumed as a raw String (spring.kafka.consumer.value-deserializer:
// StringDeserializer in application.yml), not Spring Kafka's JsonDeserializer
// — that deserializer expects a __TypeId__ header identifying the target
// class, which only a Spring Kafka PRODUCER adds automatically. User
// Service is a .NET producer that writes plain JSON with no such header, so
// this listener deserializes with its own injected ObjectMapper instead of
// relying on header-based type inference that would never be present here.
@Component
@RequiredArgsConstructor
@Slf4j
public class UserVerifiedEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventsRepository processedEventsRepository;
    private final DiscoveryEligibilityCacheService eligibilityCacheService;

    // groupId is spelled out literally, matching the kafka-outbox skill's own
    // examples (e.g. groupId = "chat-service") — every consumer in this
    // service belongs to the SAME group ("discovery-service"), so Kafka
    // balances partitions across this service's instances instead of every
    // instance independently re-reading the full topic.
    //
    // @Transactional wraps the JPA write (processedEventsRepository.save)
    // below in this service's own local database transaction — it has no
    // bearing on the Kafka offset commit itself (that is governed by
    // application.yml's spring.kafka.listener.ack-mode: BATCH with
    // enable-auto-commit: false, which commits only after this method
    // returns without throwing).
    @KafkaListener(topics = "user.verified", groupId = "discovery-service")
    @Transactional
    public void onUserVerified(String rawMessage) {
        if (rawMessage == null) {
            log.warn("Received null-value user.verified message, skipping");
            return;
        }

        UserVerifiedEvent event;
        try {
            event = objectMapper.readValue(rawMessage, UserVerifiedEvent.class);
        } catch (JsonProcessingException ex) {
            // "What NOT to do": a malformed message must never crash this
            // consumer thread — that would stall every OTHER user's events
            // queued behind it on the same partition. There is no retry that
            // fixes bad bytes, so this is a terminal log-and-skip, not a
            // thrown exception (throwing here would also prevent the offset
            // commit, causing Kafka to redeliver the same unparseable
            // message forever). Only log the exception message, not the raw
            // payload — the payload may contain sensitive fields like gender
            // that should never be exposed in application logs.
            log.error("Failed to deserialize user.verified message, skipping: {}", ex.getMessage());
            return;
        }

        // Guard clause: reject messages with null event_id before attempting
        // to use it. A null event_id would cause NullPointerException below
        // outside the try/catch, stalling the partition with an unrecoverable
        // poison pill. Log and skip instead.
        if (event.eventId() == null) {
            log.error("Received user.verified event with null event_id, skipping");
            return;
        }

        // Pattern: Idempotency — Kafka's at-least-once delivery means this
        // exact event_id can be delivered a second time (e.g. this consumer
        // crashes after the SADD below lands but before the container
        // commits the offset). Checking processed_events first, and only
        // recording it AFTER the Redis write below succeeds, is what makes
        // "processed twice" a guarantee rather than a race.
        if (processedEventsRepository.existsById(event.eventId().toString())) {
            log.info("Skipping already-processed user.verified event {}", event.eventId());
            return;
        }

        // Cache-Aside write: this Set is the eligibility gate — a newly
        // onboarded user must appear in it before any discovery scan can
        // surface them. See DiscoveryEligibilityCacheService's class comment
        // for why this lives outside ScanCacheService.
        eligibilityCacheService.addEligible(event.userId());

        processedEventsRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId().toString())
                .build());
    }
}
