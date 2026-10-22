package com.shareconnectsave.report_service.exception;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shareconnectsave.shared.GlobalExceptionHandler;

// Pattern: DRY via a shared library, activated by explicit @Import rather
// than widening @SpringBootApplication's component scan to cover
// com.shareconnectsave.shared. GlobalExceptionHandler lives outside this
// service's default scan tree (it's in shared-java-lib, package
// com.shareconnectsave.shared), so Spring will never find it on its own.
//
// Gap this ticket closes: T051 built Report/ReportReason/ReportRepository
// but never added the shared-java-lib dependency or this @Import, even
// though shared-java-lib's own header comment already claims "all five Java
// services (Discovery, Connection, Rating, Report, Admin)" depend on it.
// T051 shipped no controller, so the missing error envelope was never
// exercised — this ticket's POST /reports is report-service's FIRST
// controller, and Architecture Rule #6 (error envelope on every error)
// would silently fail without this class and the matching pom.xml
// dependency. Same "depend on precisely what you use" @Import-not-wider-scan
// discipline as connection-service's and rating-service's identical
// ExceptionHandlingConfig classes — this is also the one file that would
// need to change if shared-java-lib ever adds a second opt-in piece of
// shared infrastructure.
//
// Known follow-up (same gap T024's review flagged for connection-service,
// tracked against T091, not fixed here): GlobalExceptionHandler's catch-all
// currently lets a missing @RequestHeader (e.g. X-User-Id) fall through as
// a raw 500 instead of a clean 400.
@Configuration
@Import(GlobalExceptionHandler.class)
public class ExceptionHandlingConfig {
}
