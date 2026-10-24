package com.shareconnectsave.admin_service.exception;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shareconnectsave.shared.GlobalExceptionHandler;

// Pattern: DRY via a shared library, activated by explicit @Import rather
// than widening @SpringBootApplication's component scan to cover
// com.shareconnectsave.shared. GlobalExceptionHandler lives outside this
// service's default scan tree (it's in shared-java-lib, package
// com.shareconnectsave.shared), so Spring will never find it on its own.
// Identical in shape and purpose to discovery-service's, connection-service's,
// rating-service's, and report-service's own ExceptionHandlingConfig — same
// "depend on precisely what you use" @Import-not-wider-scan discipline.
//
// Scope note specific to this ticket: this class only covers failures that
// reach Spring MVC's DispatcherServlet (e.g. a future @Valid failure on a
// real admin endpoint). It does NOT cover a missing/invalid X-User-Role —
// that rejection happens inside Spring Security's filter chain, BEFORE the
// request ever reaches MVC, so GlobalExceptionHandler is never consulted for
// it. AdminAuthenticationEntryPoint and AdminAccessDeniedHandler (security
// package) are the only place that specific failure is shaped for this
// service, independent of this @Import.
@Configuration
@Import(GlobalExceptionHandler.class)
public class ExceptionHandlingConfig {
}
