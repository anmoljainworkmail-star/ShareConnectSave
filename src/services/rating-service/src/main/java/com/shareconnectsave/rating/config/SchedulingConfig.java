package com.shareconnectsave.rating.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling lives on its own tiny config class, not on
// RatingServiceApplication - same "keep the entry point ignorant of wiring
// detail" reasoning as ExceptionHandlingConfig's @Import, and the same
// pattern discovery-service uses for its own first @Scheduled method.
// OutboxRelay (T042) is the first @Scheduled method this service has -
// without this class, @Scheduled(fixedDelay = 500) on OutboxRelay would be
// silently ignored and no outbox row would ever reach Kafka.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
