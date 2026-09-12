package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

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
}
