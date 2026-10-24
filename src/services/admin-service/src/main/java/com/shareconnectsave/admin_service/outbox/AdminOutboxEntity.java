package com.shareconnectsave.admin_service.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Spring Data MongoDB @Document, NOT a JPA @Entity — see pom.xml's "T057
 * DATASTORE DECISION" comment for the full reasoning behind choosing Mongo
 * here. Because that decision was made specifically for this one purpose
 * (admin-service's Kafka outbox, nothing else), CLAUDE.md's "JPA @Entity
 * classes take an Entity suffix" rule does not apply to this class either —
 * same carve-out report-service's Report.java documents for the identical
 * reason (Spring Data MongoDB's generic @Id, not JPA's).
 *
 * Outbox Pattern, STANDALONE variant — contrast with report-service's Report.java:
 * Report FOLDS eventPayload/outboxPublishedAt onto an existing business
 * document (a filed report), because report.filed's whole payload IS that
 * report. admin-service's reinstate endpoint has no equivalent business
 * document of its own to fold onto — User Service, not admin-service, owns
 * the user's status; Rating Service, not admin-service, owns the trust score.
 * The ONLY local state this endpoint ever creates is the outbox row itself,
 * so this document's entire reason to exist IS the outbox row — there is
 * nothing else to fold it onto. This is still the same Outbox Pattern (a
 * single atomic document write durably records "an event is now pending"
 * before Kafka ever sees it), just with no extra business fields riding
 * alongside it.
 *
 * App-assigned id (UUID string minted in ReviewQueueServiceImpl before this
 * document is ever built), same "mint the id before the row exists so it can
 * be embedded in its own payload" technique as report-service's Report.id
 * and connection-service's OutboxEvent.id.
 *
 * @Getter, not @Data — avoids generating equals/hashCode from every field,
 * same reasoning as every other @Document/@Entity in this project.
 */
@Document(collection = "admin_outbox")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminOutboxEntity {

    @Id
    private String id;

    private String topic;

    // The aggregate this event concerns (here, the reinstated user's id) —
    // used as the Kafka partition key so every event about the same user
    // lands on one partition, same reasoning as every other outbox row in
    // this codebase.
    private String partitionKey;

    // Pre-serialized JSON (TrustScoreUpdatedEvent today; this field's type
    // stays a plain String, not tied to that one event type, so a future
    // second producer method on IOutboxService needs no schema change here).
    private String payload;

    private Instant createdAt;

    // null means "not yet relayed to Kafka" — AdminOutboxRelay's own
    // findByPublishedAtIsNullOrderByCreatedAtAsc query is built entirely
    // around that meaning, same convention as report-service's
    // outboxPublishedAt. Set only after KafkaTemplate has actually
    // acknowledged the send.
    private Instant publishedAt;
}
