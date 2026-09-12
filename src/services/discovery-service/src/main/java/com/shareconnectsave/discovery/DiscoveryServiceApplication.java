package com.shareconnectsave.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Pattern: Single Responsibility (SOLID-S) — this class does exactly one job,
// bootstrap the application. It does not know about GlobalExceptionHandler
// (shared-java-lib): that is wired in explicitly by
// exception.ExceptionHandlingConfig via @Import, not by widening this class's
// own component scan. Keeping the entry point ignorant of that detail means
// adding/removing shared infrastructure never requires touching this file.
@SpringBootApplication
public class DiscoveryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DiscoveryServiceApplication.class, args);
	}

}
