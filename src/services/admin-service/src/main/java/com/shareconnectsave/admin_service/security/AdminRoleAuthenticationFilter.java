package com.shareconnectsave.admin_service.security;

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

// Pattern: Filter chain authentication (first real Spring Security usage in
// this platform), and inside it, Single Responsibility (SOLID-S) split
// between AUTHENTICATION and AUTHORIZATION. Plain English: picture a venue
// with two separate people — one at the door who checks your ID and writes
// "this is Alice" on a visitor badge, and a second person further inside who
// decides which rooms an "Alice" badge actually opens. This class is only
// ever the first person. It decides WHO the caller claims to be, based on a
// header the gateway already set; it never decides whether that identity is
// ALLOWED past the door that matters — SecurityConfig's
// `.anyRequest().hasRole("ADMIN")` rule, evaluated later in the same chain,
// is the only place that authorization decision lives. Keeping these split
// means a second role introduced later only ever touches the authorization
// rule, never this header-reading logic, and vice versa.
//
// No JWT re-validation, by design: the API Gateway (YARP) already validated
// the signed token and is the one appending X-User-Role downstream. Doing it
// again here would duplicate that work and couple admin-service to Google's
// JWKS endpoint for no benefit — same "trust the gateway, read headers"
// convention the java-spring-boot skill documents and every other Java
// service in this platform already follows for X-User-Id/X-User-Role.
@Component
public class AdminRoleAuthenticationFilter extends OncePerRequestFilter {

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
        // continues the chain unauthenticated. This filter never rejects a
        // request itself — authentication only ever populates an identity;
        // it is SecurityConfig's authorization rule, and ultimately
        // AdminAuthenticationEntryPoint, that turns "no identity" into an
        // actual 403 further down the chain.
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
