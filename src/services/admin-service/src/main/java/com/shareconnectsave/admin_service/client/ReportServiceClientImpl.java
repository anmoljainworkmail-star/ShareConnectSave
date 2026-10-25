package com.shareconnectsave.admin_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;

// Circuit Breaker (Decorator), same shape as the other two clients — see
// ReportServiceClient's own KNOWN GAP comment for why getReportHistory below
// currently fails until Report Service ships a reportedId-filtered endpoint.
// getTopReported (T108) has no such gap — GET /reports/top-reported is a
// real, shipped endpoint.
@Component
@Slf4j
@RequiredArgsConstructor
public class ReportServiceClientImpl implements ReportServiceClient {

    // Explicit @Qualifier — see UserServiceClientImpl's field comment for why
    // this is preferred over relying on field-name-to-bean-name matching
    // across WebClientConfig's three same-typed beans.
    @Qualifier("reportServiceWebClient")
    private final WebClient reportServiceWebClient;
    private final ReportHistoryItemMapper reportHistoryItemMapper;

    @Override
    @CircuitBreaker(name = "reportService", fallbackMethod = "getReportHistoryFallback")
    public List<ReportHistoryItem> getReportHistory(String reportedUserId) {
        ReportDto[] reports = reportServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/reports").queryParam("reportedId", reportedUserId).build())
                .retrieve()
                .bodyToMono(ReportDto[].class)
                .block();

        if (reports == null) {
            return List.of();
        }

        return List.of(reports).stream()
                .map(reportHistoryItemMapper::toReportHistoryItem)
                .toList();
    }

    private List<ReportHistoryItem> getReportHistoryFallback(String reportedUserId, Throwable ex) {
        log.warn("Report Service history unavailable for user {} ({})", reportedUserId, ex.toString());
        throw new UpstreamUnavailableException("Report Service", ex);
    }

    // T108 — NO fallback, same reasoning UserServiceClientImpl.getStats
    // documents for its own dashboard metric: this call IS the Admin
    // Dashboard's "top reported users" tile's entire data source, so a
    // fabricated empty list on a Report Service outage would misinform an
    // admin into believing nobody is being reported, rather than visibly
    // failing. Still wrapped by the shared "reportService" circuit breaker
    // instance (application.yml) so a down Report Service trips open and
    // fails fast instead of hanging every dashboard load on a slow timeout.
    @Override
    @CircuitBreaker(name = "reportService")
    public List<TopReportedUser> getTopReported(int days, int limit) {
        TopReportedDto[] results = reportServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/reports/top-reported")
                        .queryParam("days", days)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(TopReportedDto[].class)
                .block();

        if (results == null) {
            // Same "2xx with no body is a contract violation" reasoning as
            // UserServiceClientImpl.getStats's own null check — fail loudly
            // rather than let a caller treat a null/empty result as a
            // legitimate "nobody has been reported".
            throw new IllegalStateException("Report Service returned no body for getTopReported()");
        }

        return List.of(results).stream()
                .map(dto -> new TopReportedUser(dto.reportedId(), dto.reportCount()))
                .toList();
    }

    // Matches ReportResponse's actual wire shape (see
    // report-service's dto/ReportResponse.java) — reason/status travel as
    // plain enum names on the wire there, deserialized here as plain Strings
    // since this client only ever displays them, never branches on them.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportDto(
            String id,
            @JsonProperty("reporter_id") String reporterId,
            @JsonProperty("reported_id") String reportedId,
            String reason,
            String status,
            @JsonProperty("created_at") Instant createdAt) {
    }

    // Matches TopReportedUserDto's actual wire shape (see report-service's
    // dto/TopReportedUserDto.java) — a deliberately separate nested record
    // from ReportDto above, not a reuse, since the two endpoints return
    // unrelated shapes (a stored report vs. a computed aggregate row).
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TopReportedDto(
            @JsonProperty("reported_id") String reportedId,
            @JsonProperty("report_count") long reportCount) {
    }
}
