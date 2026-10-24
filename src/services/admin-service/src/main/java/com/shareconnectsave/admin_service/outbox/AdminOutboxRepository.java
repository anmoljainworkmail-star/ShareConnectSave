package com.shareconnectsave.admin_service.outbox;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

// Repository (via Spring Data), plain interface, no I-prefix — same naming
// convention every Java repository in this codebase uses (see report-service's
// ReportRepository's own comment on this). Spring Data generates both plain
// CRUD (MongoRepository<AdminOutboxEntity, String>) and
// findByPublishedAtIsNullOrderByCreatedAtAsc's query body purely from the
// method name.
public interface AdminOutboxRepository extends MongoRepository<AdminOutboxEntity, String> {

    // AdminOutboxRelay's own polling query — this Mongo-adapted outbox's
    // equivalent of the SQL-backed services' "WHERE status = PENDING", same
    // shape as report-service's findByOutboxPublishedAtIsNullOrderByCreatedAtAsc.
    List<AdminOutboxEntity> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
