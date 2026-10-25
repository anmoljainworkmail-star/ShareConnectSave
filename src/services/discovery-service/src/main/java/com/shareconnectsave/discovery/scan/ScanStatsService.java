package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanStatsResponse;

// Dependency Inversion (SOLID-D) — AdminScanController depends on this
// interface, never on ScanStatsServiceImpl directly, same
// thin-controller-delegates-to-a-service-interface convention as
// ScanController depending on ScanSessionService/ScanQueryService. Kept as
// its own interface rather than a third method bolted onto ScanQueryService:
// Interface Segregation (SOLID-I) — ScanQueryService answers "who is nearby
// for THIS caller" (a discovery-feature question, X-User-Id scoped);
// ScanStatsService answers "how many scans started today, platform-wide" (an
// admin-aggregation question with no per-caller scope at all). Forcing both
// into one interface would make a future test double for one concern also
// have to fake the other.
public interface ScanStatsService {

    // Backed by ScanSessionRepository.countScansStartedToday — see that
    // method's own comment for why this is a SQL count, not a Redis read.
    ScanStatsResponse getStats();
}
