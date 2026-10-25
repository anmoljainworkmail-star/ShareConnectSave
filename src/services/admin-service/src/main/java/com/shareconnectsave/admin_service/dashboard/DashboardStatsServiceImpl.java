package com.shareconnectsave.admin_service.dashboard;

import java.util.List;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.shareconnectsave.admin_service.client.ConnectionServiceClient;
import com.shareconnectsave.admin_service.client.DiscoveryServiceClient;
import com.shareconnectsave.admin_service.client.ReportServiceClient;
import com.shareconnectsave.admin_service.client.UserServiceClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Facade-like aggregation (same shape as ReviewQueueServiceImpl's own class
// comment) — DashboardStatsController only ever asks THIS class for "the
// dashboard", never the four client interfaces directly.
//
// Single Responsibility (SOLID-S): this class only ASSEMBLES the aggregate
// view. It does not calculate trust scores, does not decide escalation, and
// does not own any of the underlying counts — each source service (User,
// Discovery, Connection, Report) remains the one place that count is
// actually computed. Nothing in this class survives a restart except the
// Caffeine cache entry, and even that is a derived view, never a system of
// record.
//
// Dependency Inversion (SOLID-D): every collaborator is an interface —
// swappable with an in-memory fake in a future unit test without a single
// real WebClient/Caffeine call.
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardStatsServiceImpl implements DashboardStatsService {

    // Single fixed cache key — see CacheConfig's own comment for why this
    // endpoint has exactly one cache entry, ever.
    private static final String CACHE_KEY = "dashboard-stats";

    // Per the ticket's own spec: top 5, by report count, last 7 days.
    private static final int TOP_REPORTED_DAYS = 7;
    private static final int TOP_REPORTED_LIMIT = 5;

    private final UserServiceClient userServiceClient;
    private final DiscoveryServiceClient discoveryServiceClient;
    private final ConnectionServiceClient connectionServiceClient;
    private final ReportServiceClient reportServiceClient;
    private final Cache<String, DashboardStatsResponse> dashboardStatsCache;

    // Cache-Aside pattern (see CacheConfig's own plain-English explanation) —
    // a manual lookup/populate here, not @Cacheable, specifically so the
    // "never cache a partial/degraded result" rule below is an explicit,
    // inspectable `if`, not hidden behind annotation magic that would cache
    // every return value unconditionally.
    @Override
    public DashboardStatsResponse getStats() {
        DashboardStatsResponse cached = dashboardStatsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }

        DashboardStatsResponse assembled = assembleStats();

        if (isFullyAvailable(assembled)) {
            dashboardStatsCache.put(CACHE_KEY, assembled);
        }
        // Deliberately NOT cached when any slice is degraded: caching a
        // partial snapshot would serve a stale "unavailable" placeholder for
        // the full 60s TTL even after the struggling dependency recovers.
        // Returning it uncached means the very next request retries every
        // downstream call instead.

        return assembled;
    }

    // Partial-Failure Degradation (same precedent as
    // ReviewQueueServiceImpl.getUserDetail/getQueue, T057) — each of the five
    // downstream calls gets its OWN try/catch, never one try/catch around all
    // five, so one struggling dependency degrades only its own tile.
    //
    // Flagged deviation from this ticket's literal wording (worth a Tech Lead
    // look): the ticket's own "Critical correctness rule" says to catch
    // UpstreamUnavailableException at each of these five call sites, the same
    // type ReviewQueueServiceImpl catches around its three calls. But
    // ReviewQueueServiceImpl's three calls ALL have a dedicated
    // @CircuitBreaker fallbackMethod guaranteeing that exact exception type.
    // Four of these five calls (UserServiceClient.getStats,
    // DiscoveryServiceClient.getStats, ConnectionServiceClient.getStats,
    // ReportServiceClient.getTopReported) deliberately have NO fallback
    // method at all — see each of their own class comments ("this IS the
    // metric's entire data source, a fabricated fallback would misinform an
    // admin"). With no fallback, Resilience4j lets the real failure through
    // unchanged: CallNotPermittedException when the breaker is open,
    // WebClientResponseException/WebClientRequestException/
    // IllegalStateException when it is closed but the call itself fails —
    // never UpstreamUnavailableException. Catching only
    // UpstreamUnavailableException here would silently let four of five
    // tiles crash the whole /admin/stats request on any real upstream
    // outage, the exact opposite of "Partial-Failure Degradation." Catching
    // RuntimeException at each site is what actually delivers the ticket's
    // own guarantee; getReportsToday (whose fallback DOES throw
    // UpstreamUnavailableException, same shape as getReportHistory) is
    // caught the same uniform way for consistency, since
    // UpstreamUnavailableException is itself a RuntimeException.
    private DashboardStatsResponse assembleStats() {
        Long totalActiveUsers = null;
        boolean totalActiveUsersUnavailable = false;
        try {
            totalActiveUsers = userServiceClient.getStats().totalActiveUsers();
        } catch (RuntimeException e) {
            log.warn("User Service stats unavailable for dashboard ({})", e.toString());
            totalActiveUsersUnavailable = true;
        }

        Long totalScansToday = null;
        boolean totalScansTodayUnavailable = false;
        try {
            totalScansToday = discoveryServiceClient.getStats().totalScansToday();
        } catch (RuntimeException e) {
            log.warn("Discovery Service stats unavailable for dashboard ({})", e.toString());
            totalScansTodayUnavailable = true;
        }

        Integer totalConnectionsAcceptedToday = null;
        boolean totalConnectionsAcceptedTodayUnavailable = false;
        try {
            totalConnectionsAcceptedToday = connectionServiceClient.getStats();
        } catch (RuntimeException e) {
            log.warn("Connection Service stats unavailable for dashboard ({})", e.toString());
            totalConnectionsAcceptedTodayUnavailable = true;
        }

        Integer totalReportsToday = null;
        boolean totalReportsTodayUnavailable = false;
        try {
            totalReportsToday = reportServiceClient.getReportsToday();
        } catch (RuntimeException e) {
            log.warn("Report Service reports-today unavailable for dashboard ({})", e.toString());
            totalReportsTodayUnavailable = true;
        }

        List<DashboardStatsResponse.TopReportedUserSummary> topReportedUsers = List.of();
        boolean topReportedUsersUnavailable = false;
        try {
            topReportedUsers = reportServiceClient.getTopReported(TOP_REPORTED_DAYS, TOP_REPORTED_LIMIT).stream()
                    .map(user -> new DashboardStatsResponse.TopReportedUserSummary(
                            user.reportedId(), user.reportCount()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Report Service top-reported unavailable for dashboard ({})", e.toString());
            topReportedUsersUnavailable = true;
        }

        return new DashboardStatsResponse(
                totalActiveUsers,
                totalActiveUsersUnavailable,
                totalScansToday,
                totalScansTodayUnavailable,
                totalConnectionsAcceptedToday,
                totalConnectionsAcceptedTodayUnavailable,
                totalReportsToday,
                totalReportsTodayUnavailable,
                topReportedUsers,
                topReportedUsersUnavailable);
    }

    private boolean isFullyAvailable(DashboardStatsResponse response) {
        return !response.totalActiveUsersUnavailable()
                && !response.totalScansTodayUnavailable()
                && !response.totalConnectionsAcceptedTodayUnavailable()
                && !response.totalReportsTodayUnavailable()
                && !response.topReportedUsersUnavailable();
    }
}
