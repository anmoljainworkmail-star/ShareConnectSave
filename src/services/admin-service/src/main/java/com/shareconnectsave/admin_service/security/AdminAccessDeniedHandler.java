package com.shareconnectsave.admin_service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.shared.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Pattern: Strategy, same shape as AdminAuthenticationEntryPoint —
// AccessDeniedHandler is the Spring Security interface for the
// "authenticated, but not authorized" case, as opposed to
// AuthenticationEntryPoint's "not authenticated at all." Spring Security's
// contract requires both beans to exist even though, given today's binary
// logic in AdminRoleAuthenticationFilter (grant ROLE_ADMIN, or grant
// nothing), this handler cannot actually be reached yet — there is no
// authenticated-but-insufficient-role identity this filter ever produces.
// It is kept anyway for correctness against Spring Security's API and as a
// seam already in place the day a second role (e.g. "support") is
// introduced and genuinely can reach an admin-only endpoint while
// authenticated.
//
// Same reasoning as AdminAuthenticationEntryPoint for why this class exists
// at all instead of relying on shared-java-lib's GlobalExceptionHandler:
// Spring Security's filter chain runs before Spring MVC's dispatcher, so a
// @RestControllerAdvice is never in the picture for this rejection path.
@Component
@RequiredArgsConstructor
public class AdminAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                        HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse("FORBIDDEN", "Admin role required", traceId());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    // Deliberately duplicated rather than extracted into a shared helper:
    // same small-and-local precedent every @RestControllerAdvice in this
    // platform already follows (see ReportExceptionHandler's and
    // GlobalExceptionHandler's own traceId() methods) — a three-line MDC
    // read isn't worth a shared utility class and the coupling it would add
    // between two otherwise-independent handler beans.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
