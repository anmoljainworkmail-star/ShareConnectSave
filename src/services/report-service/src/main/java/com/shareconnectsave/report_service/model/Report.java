package com.shareconnectsave.report_service.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Spring Data MongoDB @Document, NOT a JPA @Entity — this is the only Java
 * service in the platform persisting to MongoDB instead of SQL Server, so
 * CLAUDE.md's "JPA @Entity classes take an Entity suffix" naming rule does
 * not extend here; it was written for JPA specifically, and MongoRepository
 * <Report, String> is this ticket's own spec'd signature. @Id here is Spring
 * Data's generic id annotation (org.springframework.data.annotation.Id),
 * not JPA's javax/jakarta.persistence.Id.
 *
 * T052 change: id is now APP-ASSIGNED (ReportServiceImpl sets a
 * UUID.randomUUID().toString() before the first save()), not Mongo-generated.
 * T051 let Mongo mint an ObjectId hex string automatically because nothing
 * needed to know the id before it existed; T052 needs the opposite — the
 * id has to be embedded inside this report's OWN eventPayload before the
 * document is ever persisted, so it must be minted in application code
 * first. Same "mint the id before the row exists so it can be embedded in
 * its own payload" technique connection-service's OutboxEvent.id uses on
 * the SQL side.
 *
 * Outbox Pattern, Mongo-adapted (see ReportOutboxRelay for the read side):
 * report-service has no SQL database, and this project's dev Mongo instance
 * is a single standalone node (not a replica set), so it cannot do
 * multi-document transactions either — a separate outbox collection would
 * need one to get the same atomicity a SQL transaction gives the SQL-backed
 * services for free, and wouldn't have it. Instead, eventPayload and
 * outboxPublishedAt live directly on THIS document, folded onto the
 * business data — chat-service's ChatRoomEntity (T039,
 * OutboxEventType/OutboxPayload/OutboxCreatedAt/OutboxPublishedAt) already
 * set this precedent for Mongo-only producers in this codebase. A single
 * MongoDB document write is already atomic even on a standalone node with
 * no transaction involved, so saving the report and marking "an event is
 * now pending for it" in the SAME ReportRepository.save(...) call closes
 * the same split-brain window the SQL outbox tables close with a DB
 * transaction instead.
 *
 * @Getter, not @Data: avoids generating equals/hashCode from every field,
 * the same reasoning the java-spring-boot skill gives for JPA entities —
 * even though Mongo documents have no lazy-loading proxies to break, a
 * reflexive @Data habit here would still produce equals/hashCode nobody
 * asked for or reviewed.
 */
@Document(collection = "reports")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {

    @Id
    private String id;

    private String reporterId;

    private String reportedId;

    private ReportReason reason;

    // Nullable — set only when the report originates from inside a chat
    // (REQUIREMENTS.md §8). The OpenAPI contract already models this even
    // though SPECS.md's one-line spec omits it; dropping it here would
    // silently break that flow.
    private String connectionId;

    // Nullable, max 1000 chars (enforced at the DTO boundary via
    // @Size — Mongo itself has no column-length constraint to lean on).
    private String details;

    // Defaults to OPEN at creation (see the no-arg @Builder.Default below).
    // Only T053's escalation logic ever transitions this away from OPEN —
    // this ticket only ever writes OPEN, never reads or branches on this
    // field's value.
    @Builder.Default
    private ReportStatus status = ReportStatus.OPEN;

    // Null until the FIRST moment this reportedId ever crosses
    // report.escalation.threshold (T053) — a snapshot of
    // report_count_for_target taken at that instant, not a live count.
    // Snapshot over live computation (same call T052 already made for
    // report_count_for_target itself): GET /reports/queue sorts on this
    // field directly, so the queue's order never changes underneath an
    // admin mid-review just because a NEW unrelated report was filed
    // against someone else, and never needs a live aggregation query.
    private Integer reportCountAtEscalation;

    private Instant createdAt;

    // The pre-serialized report.filed JSON this report's own outbox "row"
    // carries. Written once, by ReportServiceImpl, in the same save() call
    // that persists the business fields above — see this class's own
    // header comment for why that single write is what makes this a valid
    // Outbox Pattern implementation without a transaction.
    private String eventPayload;

    // null means "not yet relayed to Kafka" — ReportOutboxRelay's own
    // findByOutboxPublishedAtIsNullOrderByCreatedAtAsc query is built
    // entirely around that meaning. Set (via a targeted MongoTemplate
    // partial update, never a full re-save) only after KafkaTemplate has
    // actually acknowledged the send.
    private Instant outboxPublishedAt;
}
