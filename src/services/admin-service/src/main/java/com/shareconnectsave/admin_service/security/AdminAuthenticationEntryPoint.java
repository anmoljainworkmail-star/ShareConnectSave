package com.shareconnectsave.admin_service.security;

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

// Pattern: Strategy, via one of Spring Security's own pluggable interfaces.
// AuthenticationEntryPoint is the hook Spring Security calls into whenever
// an unauthenticated request reaches a protected endpoint — swapping in this
// implementation changes HOW that rejection is reported on the wire, without
// touching HOW the rejection is detected (AdminRoleAuthenticationFilter's
// header check + SecurityConfig's hasRole("ADMIN") rule own that, upstream).
//
// Why this class has to exist at all, when every other Java service in this
// platform gets its error envelope for free from shared-java-lib's
// GlobalExceptionHandler: that class is a @RestControllerAdvice, which only
// ever runs for exceptions thrown INSIDE Spring MVC's DispatcherServlet.
// Spring Security's filter chain runs BEFORE the DispatcherServlet — a
// request missing X-User-Role never reaches MVC at all, so
// GlobalExceptionHandler is never given the chance to shape its response.
// This class, called directly by Spring Security itself, is the only place
// in admin-service that failure path can be shaped — which is also why it
// reuses shared-java-lib's ErrorResponse record directly rather than
// hand-rolling a new JSON shape: the two failure paths (MVC-handled,
// Security-handled) must stay byte-for-byte identical on the wire even
// though no shared code path produces them.
//
// 403, not 401: this project treats "no valid admin role" as a flat access
// denial. There is no login page to redirect to and no WWW-Authenticate
// challenge that makes sense for a gateway-to-service call — unlike a
// browser-facing login flow, "retry with different credentials" isn't a
// real next step here, so Forbidden is the honest status, same choice this
// project's other services make for authorization (not authentication)
// failures.
@Component
@RequiredArgsConstructor
public class AdminAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                          HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse("FORBIDDEN", "Admin role required", traceId());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    // Same MDC-read convention as shared-java-lib's GlobalExceptionHandler and
    // every other local handler in this platform: traceId is only ever READ
    // from the OpenTelemetry-populated request-thread context, never minted
    // here — a generated id would not exist in Jaeger, defeating the point of
    // correlating a client-visible error back to a real trace. Always "" until
    // T087 wires MDC.put("traceId", ...), same known gap documented in every
    // other service's handler.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
