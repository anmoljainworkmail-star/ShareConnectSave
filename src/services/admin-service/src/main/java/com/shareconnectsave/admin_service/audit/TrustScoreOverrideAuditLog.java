package com.shareconnectsave.admin_service.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

// Repository-shaped wrapper around an in-memory store (GoF Repository, plain
// English: a small object that hides "where the data actually lives" behind
// a simple collection-like interface — think of a cloakroom attendant: you
// hand over your coat and get a ticket back, never needing to know which peg
// it ends up on). AdminTrustScoreServiceImpl calls record(...) the same way
// it would call a real JpaRepository.save(...); the ONLY thing that changes
// when this becomes a real table later is this one class's insides.
//
// SWAP POINT FOR A FUTURE TICKET: this in-memory CopyOnWriteArrayList is the
// placeholder the ticket's own "file-backed in prod" note refers to. A future
// ticket replaces the field below with a real repository (JPA table or an
// append-only file) WITHOUT AdminTrustScoreServiceImpl's call site changing
// at all — Liskov Substitution (SOLID-L): any replacement must keep behaving
// like this one to every caller.
//
// CopyOnWriteArrayList, not a plain ArrayList: admin-service is a multi-
// threaded servlet container (one thread per request) and audit writes must
// never be lost to a race between two concurrent overrides — the same
// "shared mutable state needs a concurrency-safe container" reasoning that
// makes a bare HashMap unsafe here. A real table's transaction isolation
// replaces this guarantee once the swap above happens.
@Component
public class TrustScoreOverrideAuditLog {

    private final List<TrustScoreOverrideAuditEntry> entries = new CopyOnWriteArrayList<>();

    public TrustScoreOverrideAuditEntry record(String adminUserId, String targetUserId,
                                                BigDecimal newScore, String reason) {
        TrustScoreOverrideAuditEntry entry = new TrustScoreOverrideAuditEntry(
                UUID.randomUUID().toString(),
                adminUserId,
                targetUserId,
                newScore,
                reason,
                Instant.now());
        entries.add(entry);
        return entry;
    }

    // Exposed for a future admin UI / audit-review ticket (and for manual QA
    // per this ticket's own checklist) — read-only, never mutated through
    // this accessor.
    public List<TrustScoreOverrideAuditEntry> findAll() {
        return List.copyOf(entries);
    }
}
