package com.shareconnectsave.discovery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling lives on its own tiny config class, not on
// DiscoveryServiceApplication — same "keep the entry point ignorant of
// wiring detail" reasoning as ExceptionHandlingConfig's @Import. T024's
// BleTokenCleanupTask is the first @Scheduled method this service has;
// this is the one class that turns Spring's scheduling machinery on for it.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
