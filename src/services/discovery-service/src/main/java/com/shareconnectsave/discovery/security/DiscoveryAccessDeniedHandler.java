package com.shareconnectsave.discovery.security;

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

// Pattern: Strategy, same shape as DiscoveryAuthenticationEntryPoint and
// admin-service's AdminAccessDeniedHandler (T056) — AccessDeniedHandler is
// the "authenticated, but not authorized" counterpart to
// AuthenticationEntryPoint's "not authenticated at all". Spring Security's
// @PreAuthorize contract requires both beans to exist even though, given
// DiscoveryAdminRoleAuthenticationFilter's current binary logic (grant
// ROLE_ADMIN, or grant nothing), this handler can never actually be reached
// yet — there is no authenticated-but-insufficient-role identity that
// filter ever produces. Kept anyway for correctness against Spring
// Security's API and as a seam already in place the day a second role
// (e.g. "support") is introduced and genuinely can reach this
// @PreAuthorize-guarded endpoint while authenticated.
@Component
@RequiredArgsConstructor
public class DiscoveryAccessDeniedHandler implements AccessDeniedHandler {

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

    // Deliberately duplicated rather than extracted into a shared helper —
    // same small-and-local precedent admin-service's own
    // AdminAccessDeniedHandler/AdminAuthenticationEntryPoint already follow:
    // a three-line MDC read isn't worth a shared utility class and the
    // coupling it would add between two otherwise-independent handler beans.
    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
