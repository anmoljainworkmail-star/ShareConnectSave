package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

// Pattern: Repository (GoF/DDD) — same shape as IUserRepository in User
// Service: ScanSessionService asks for a ScanSession by id or by "this user's
// active session", never writes a JPQL/SQL query itself. Spring Data derives
// both methods below from their names at startup; no implementation to
// maintain by hand.
public interface ScanSessionRepository extends JpaRepository<ScanSession, Long> {

    // "Active" == ended_at IS NULL. OrderByIdDesc + findFirst guards against
    // a user who somehow has more than one open session (e.g. a crashed
    // client that never called /scan/stop) resolving to a single, most-recent
    // row instead of an ambiguous set.
    //
    // Pattern: Pessimistic locking (TOCTOU race prevention) — PUT
    // /scan/location and POST /scan/stop both do a read-then-act on the same
    // "active session" row. Without a DB-held lock, stop() could commit its
    // ended_at write and delete the Redis key, then a location update that
    // read the (still-active-looking) session a moment earlier could write a
    // fresh Redis key back in — resurrecting a "live" GPS coordinate after
    // the user stopped scanning. PESSIMISTIC_WRITE takes a row lock (SQL
    // Server: an UPDLOCK/ROWLOCK-style hint under the hood) for the duration
    // of the caller's transaction, so whichever of {stop, location-update}
    // gets there first finishes its entire check-then-act sequence —
    // including the Redis call — before the other can even read the row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScanSession> findFirstByUserIdAndEndedAtIsNullOrderByIdDesc(Long userId);

    // T106 — Distinct Query Intent (Analytics vs. Real-Time): "how many scan
    // sessions started today" is an ANALYTICS question answered from this
    // table, deliberately NOT from Redis's scan:active_sessions key (T023),
    // which only ever holds sessions that are CURRENTLY active and answers a
    // different question ("how many people are scanning right now"). A
    // session a user already stopped hours ago still counts here, since
    // started_at is never cleared by /scan/stop (see ScanSession.close(),
    // which only sets ended_at) — exactly the historical count the Admin
    // Dashboard needs.
    //
    // An explicit @Query, not a derived findBy... method name, because
    // "today" is a caller-supplied UTC day boundary (ScanStatsServiceImpl
    // computes it via ZonedDateTime.now(UTC).truncatedTo(DAYS)), not a
    // column value Spring Data can infer from the method name alone — the
    // boundary must be passed in, not hardcoded into generated SQL.
    @Query("SELECT COUNT(s) FROM ScanSession s WHERE s.startedAt >= :startOfDayUtc")
    long countScansStartedToday(@Param("startOfDayUtc") Instant startOfDayUtc);
}
