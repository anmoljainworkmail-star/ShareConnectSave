package com.shareconnectsave.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Pattern: Single Responsibility (SOLID-S) — this class does exactly one job,
// bootstrap the application. It does not know about GlobalExceptionHandler
// (shared-java-lib): that is wired in explicitly by
// exception.ExceptionHandlingConfig via @Import, not by widening this class's
// own component scan. Keeping the entry point ignorant of that detail means
// adding/removing shared infrastructure never requires touching this file.
//
// @ConfigurationPropertiesScan (Options pattern, T023): auto-registers every
// @ConfigurationProperties-annotated record found under this package
// (DiscoveryProperties, UserServiceProperties) as a bean, the same way
// .NET's builder.Services.Configure<T>(...) registers an IOptions<T> — one
// annotation here, instead of an explicit @Bean method per properties class.
@SpringBootApplication
@ConfigurationPropertiesScan
public class DiscoveryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DiscoveryServiceApplication.class, args);
	}

}
