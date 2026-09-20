package com.shareconnectsave.connection.exception;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shareconnectsave.shared.GlobalExceptionHandler;

// Pattern: DRY via a shared library, activated by explicit @Import rather than
// widening @SpringBootApplication's component scan to cover
// com.shareconnectsave.shared. GlobalExceptionHandler lives outside this
// service's default scan tree (it's in shared-java-lib, package
// com.shareconnectsave.shared), so Spring will never find it on its own.
//
// The alternative — @SpringBootApplication(scanBasePackages = {"com.shareconnectsave.connection",
// "com.shareconnectsave.shared"}) on ConnectionServiceApplication — was
// considered and rejected: it pulls in EVERYTHING under com.shareconnectsave.shared
// as that package grows (any future @Component/@Service the shared lib adds
// gets silently activated here too, whether this service wants it or not).
// @Import names the exact one class this service depends on, nothing more —
// the same "depend on precisely what you use" discipline as Interface
// Segregation (SOLID-I), applied to package scanning instead of interfaces.
// This is also the ONLY file in this service that needs to change if
// shared-java-lib ever adds a second, opt-in-only piece of shared
// infrastructure — ConnectionServiceApplication itself stays untouched.
//
// Known follow-up (from T024's review, tracked against T091, not fixed here):
// GlobalExceptionHandler's catch-all currently lets a missing @RequestHeader
// (e.g. X-User-Id) fall through as a raw 500 instead of a clean 400. This
// ticket adds no controllers, so it isn't triggered yet — but the first
// Connection Service controller that reads X-User-Id inherits this gap until
// shared-java-lib itself is fixed.
@Configuration
@Import(GlobalExceptionHandler.class)
public class ExceptionHandlingConfig {
}
