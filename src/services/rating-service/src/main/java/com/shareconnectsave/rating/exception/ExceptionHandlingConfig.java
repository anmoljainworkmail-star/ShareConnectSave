package com.shareconnectsave.rating.exception;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shareconnectsave.shared.GlobalExceptionHandler;

// Pattern: DRY via a shared library, activated by explicit @Import rather
// than widening @SpringBootApplication's component scan to cover
// com.shareconnectsave.shared. GlobalExceptionHandler lives outside this
// service's default scan tree (it's in shared-java-lib, package
// com.shareconnectsave.shared), so Spring will never find it on its own.
//
// Same "depend on precisely what you use" discipline as Interface
// Segregation (SOLID-I), applied to package scanning instead of interfaces —
// identical in shape to discovery-service's own ExceptionHandlingConfig
// (T021). This is the ONLY file in this service that needs to change if
// shared-java-lib ever adds a second, opt-in-only piece of shared
// infrastructure.
@Configuration
@Import(GlobalExceptionHandler.class)
public class ExceptionHandlingConfig {
}
