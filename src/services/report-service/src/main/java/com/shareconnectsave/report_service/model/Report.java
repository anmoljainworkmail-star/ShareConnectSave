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
 * not JPA's javax/jakarta.persistence.Id — Mongo generates the id (an
 * ObjectId hex string) itself when one isn't supplied, same role
 * SQL Server's IDENTITY column plays for the JPA services, just driven by
 * a different persistence technology.
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

    private Instant createdAt;
}
