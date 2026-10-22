package com.shareconnectsave.report_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling lives on its own tiny config class, not on
// ReportServiceApplication — same "keep the entry point ignorant of wiring
// detail" reasoning as ExceptionHandlingConfig's @Import, and the same
// pattern rating-service's own SchedulingConfig uses for its first
// @Scheduled method. ReportOutboxRelay (T052) is the first @Scheduled
// method this service has — without this class, @Scheduled(fixedDelay =
// 500) on ReportOutboxRelay would be silently ignored and no report.filed
// event would ever reach Kafka.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
