package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class ScanStatsServiceImpl implements ScanStatsService {

    private final ScanSessionRepository scanSessionRepository;

    @Override
    public ScanStatsResponse getStats() {
        // UTC day boundary computed here, not inside the repository query
        // itself: "what counts as the start of today" is an application-
        // level decision (always UTC, never the server JVM's local zone or a
        // caller-supplied timezone), while the repository's own job stays
        // limited to "count rows at or after a given instant" — same
        // Single Responsibility split as ScanFilterService owning filter
        // math while ScanSessionRepository only ever executes a query it is
        // handed.
        Instant startOfDayUtc = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.DAYS).toInstant();
        long totalScansToday = scanSessionRepository.countScansStartedToday(startOfDayUtc);
        return new ScanStatsResponse(totalScansToday);
    }
}
