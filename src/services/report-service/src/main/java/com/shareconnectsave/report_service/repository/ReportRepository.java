package com.shareconnectsave.report_service.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.shareconnectsave.report_service.model.Report;
import com.shareconnectsave.report_service.model.ReportStatus;

/**
 * Repository (via Spring Data): a plain interface, no implementation class
 * written by hand — Spring Data generates both the CRUD implementation
 * (from MongoRepository<Report, String>) AND the query body for
 * findByReportedId purely from the method name, at startup via a JDK
 * dynamic proxy. Same "hide where the data actually lives behind a simple
 * collection-like interface" idea as every other XxxRepository in this
 * project (e.g. IUserRepository on the .NET side, discovery-service's JPA
 * repositories on the Java side) — here the framework writes the concrete
 * class instead of a developer hand-rolling it.
 *
 * Naming: plain ReportRepository, never IReportRepository — Java
 * repositories in this codebase are plain interfaces with no I-prefix,
 * unlike the C# services.
 */
public interface ReportRepository extends MongoRepository<Report, String> {

    List<Report> findByReportedId(String reportedId);

    // T052 — ReportOutboxRelay's own polling query. "OutboxPublishedAtIsNull"
    // is this Mongo-adapted outbox's equivalent of the SQL-backed services'
    // "WHERE status = PENDING" — see Report's own class comment for why
    // outboxPublishedAt (not a separate outbox table/status column) is the
    // signal here. OrderByCreatedAtAsc + Pageable gives the relay the same
    // oldest-first, bounded-batch shape as connection-service's
    // findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0,
    // BATCH_SIZE)).
    List<Report> findByOutboxPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    // T053 — GET /reports/queue's entire query. Repository (via Spring
    // Data), same "framework writes the query from the method name" idea as
    // findByReportedId above: the admin review queue is every ESCALATED
    // report, ordered by the reportCountAtEscalation snapshot stamped at
    // escalation time (descending — worst offenders first), with no
    // live aggregation pipeline written by hand.
    List<Report> findByStatusOrderByReportCountAtEscalationDesc(ReportStatus status);
}
