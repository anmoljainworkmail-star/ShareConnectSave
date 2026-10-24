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
// ReportServiceClient's own KNOWN GAP comment for why every call here
// currently fails until Report Service ships a reportedId-filtered endpoint.
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
}
