package com.shareconnectsave.discovery.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Pattern: same Single Responsibility (SOLID-S) split between AUTHENTICATION
// and AUTHORIZATION that admin-service's AdminRoleAuthenticationFilter (T056)
// teaches, copied here deliberately rather than shared, because the two
// services sit on opposite sides of a Database per Service-style boundary —
// there is no shared "security" module between Java microservices in this
// platform, each one's filter reads the exact same X-User-Role header
// independently. Plain English: this class is only ever the door person who
// checks an ID and writes a name on a visitor badge ("this caller claims to
// be admin") — it never decides which rooms that badge opens. That decision
// belongs entirely to @PreAuthorize("hasRole('ADMIN')") on
// AdminScanController.getStats(), evaluated later for this one method only
// (NOT a filter-chain-wide authorization rule, unlike admin-service's own
// SecurityConfig) — see this package's SecurityConfig for why method security
// is this ticket's deliberate point of comparison against T056/T104's two
// other real answers to "is this caller an admin".
//
// No JWT re-validation, by design: the API Gateway (YARP) already validated
// the signed token and is the one appending X-User-Role downstream — same
// "trust the gateway, read headers" convention every Java service in this
// platform already follows for X-User-Id/X-User-Gender (see ScanController).
@Component
public class DiscoveryAdminRoleAuthenticationFilter extends OncePerRequestFilter {

    private static final String ADMIN_ROLE_HEADER = "X-User-Role";
    private static final String ADMIN_ROLE_VALUE = "admin";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String role = request.getHeader(ADMIN_ROLE_HEADER);

        // Guard clause over a nested if/else: anything other than an exact,
        // case-sensitive "admin" match (missing, blank, "Admin", some other
        // role entirely) leaves the SecurityContext untouched and simply
        // continues the chain unauthenticated — never rejects itself here.
        // With SecurityConfig's filter-chain rule set to permitAll(), an
        // unauthenticated request reaches every OTHER endpoint (/scan/*,
        // /actuator/health) exactly as it did before this ticket; it only
        // fails once it reaches AdminScanController.getStats()'s
        // @PreAuthorize check, which has no Authentication to evaluate
        // hasRole("ADMIN") against.
        if (!ADMIN_ROLE_VALUE.equals(role)) {
            filterChain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                role,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
