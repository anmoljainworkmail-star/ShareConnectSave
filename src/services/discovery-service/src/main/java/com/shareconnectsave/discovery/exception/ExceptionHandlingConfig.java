package com.shareconnectsave.discovery.exception;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shareconnectsave.shared.GlobalExceptionHandler;

// Pattern: DRY via a shared library, activated by explicit @Import rather than
// widening @SpringBootApplication's component scan to cover
// com.shareconnectsave.shared. GlobalExceptionHandler lives outside this
// service's default scan tree (it's in shared-java-lib, package
// com.shareconnectsave.shared), so Spring will never find it on its own.
//
// The alternative — @SpringBootApplication(scanBasePackages = {"com.shareconnectsave.discovery",
// "com.shareconnectsave.shared"}) on DiscoveryServiceApplication — was
// considered and rejected: it pulls in EVERYTHING under com.shareconnectsave.shared
// as that package grows (any future @Component/@Service the shared lib adds
// gets silently activated here too, whether this service wants it or not).
// @Import names the exact one class this service depends on, nothing more —
// the same "depend on precisely what you use" discipline as Interface
// Segregation (SOLID-I), applied to package scanning instead of interfaces.
// This is also the ONLY file in this service that needs to change if
// shared-java-lib ever adds a second, opt-in-only piece of shared
// infrastructure — DiscoveryServiceApplication itself stays untouched.
@Configuration
@Import(GlobalExceptionHandler.class)
public class ExceptionHandlingConfig {
}
