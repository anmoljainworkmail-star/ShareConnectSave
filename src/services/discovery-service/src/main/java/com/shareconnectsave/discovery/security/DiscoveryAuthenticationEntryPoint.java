package com.shareconnectsave.discovery.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.shared.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Pattern: Strategy, via Spring Security's own pluggable
// AuthenticationEntryPoint interface — same shape as admin-service's
// AdminAuthenticationEntryPoint (T056). Fires only for the "no Authentication
// at all" case: a missing/wrong X-User-Role means
// DiscoveryAdminRoleAuthenticationFilter never populated the
// SecurityContext, so when @PreAuthorize("hasRole('ADMIN')") on
// AdminScanController.getStats() evaluates, there is no Authentication
// object for it to check — Spring Security treats that as "not
// authenticated" and calls this entry point, not AccessDeniedHandler (that
// one is reserved for an authenticated caller who still lacks the role,
// which this filter's current binary admin/not-admin logic can never
// produce today).
//
// Why this class has to exist at all, when every other error in this
// service already gets the shared envelope for free from shared-java-lib's
// GlobalExceptionHandler (see exception.ExceptionHandlingConfig): that class
// is a @RestControllerAdvice, which only runs for exceptions thrown INSIDE
// Spring MVC's DispatcherServlet. A method-security rejection happens
// earlier than that — Spring Security's AOP interceptor evaluates
// @PreAuthorize before the controller method body ever runs, and this
// project's Architecture Rule #6 requires the shared {code, message,
// traceId} envelope on that rejection too, not Spring Security's bare
// default 403 body.
@Component
@RequiredArgsConstructor
public class DiscoveryAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                          HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse("FORBIDDEN", "Admin role required", traceId());

        // 403, not 401 — same reasoning as admin-service's
        // AdminAuthenticationEntryPoint: there is no login page to redirect
        // to and no WWW-Authenticate challenge that makes sense for a
        // gateway-to-service call, so Forbidden is the honest status here
        // too.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    // Same MDC-read convention as every other handler in this platform:
    // traceId is only ever READ from the OpenTelemetry-populated
    // request-thread context, never minted here. Always "" until T087 wires
    // MDC.put("traceId", ...), same known gap every other service's handler
    // already documents.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
