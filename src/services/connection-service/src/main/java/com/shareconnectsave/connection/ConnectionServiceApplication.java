package com.shareconnectsave.connection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Pattern: Single Responsibility (SOLID-S) — this class does exactly one job,
// bootstrap the application. It does not know about GlobalExceptionHandler
// (shared-java-lib): that is wired in explicitly by
// exception.ExceptionHandlingConfig via @Import, not by widening this class's
// own component scan. Keeping the entry point ignorant of that detail means
// adding/removing shared infrastructure never requires touching this file.
//
// @EnableScheduling: turns on Spring's @Scheduled processing for the whole
// application context — without it, ConnectionExpiryScheduler's
// @Scheduled(fixedRate = ...) method is just a plain unannotated-looking
// method that Spring never calls. This is a startup-time switch (like
// @EnableJpaRepositories), which is why it lives on the composition root
// rather than on ConnectionExpiryScheduler itself; this is also the FIRST
// @Scheduled job anywhere in the platform, so this annotation is new to
// connection-service as of this ticket, not carried over from T029/T030.
@EnableScheduling
@SpringBootApplication
public class ConnectionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConnectionServiceApplication.class, args);
	}

}
