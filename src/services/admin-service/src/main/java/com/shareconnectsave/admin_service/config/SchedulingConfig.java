package com.shareconnectsave.admin_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling lives on its own tiny config class, not on
// AdminServiceApplication — same "keep the entry point ignorant of wiring
// detail" split report-service's and rating-service's SchedulingConfig
// already use. AdminOutboxRelay (T057) is this service's first @Scheduled
// method — without this class, @Scheduled(fixedDelay = 500) on it would be
// silently ignored and no trust.score.updated event would ever reach Kafka
// on reinstate.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
