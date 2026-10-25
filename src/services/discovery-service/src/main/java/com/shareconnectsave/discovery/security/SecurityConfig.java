package com.shareconnectsave.discovery.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Pattern: Method Security (@PreAuthorize) — this service's first-ever
// Spring Security usage, and the THIRD real implementation in this codebase
// of the exact same "is this caller an admin" question, each at a different
// layer:
//   1. admin-service's T056 SecurityConfig — a path-matcher
//      SecurityFilterChain rule (`.anyRequest().hasRole("ADMIN")`) gates
//      EVERY endpoint in that service from one central place, because
//      admin-service's whole reason to exist is admin-only actions.
//   2. connection-service's T104/T107 AdminConnectionController — a
//      hand-written `if (!"admin".equals(role)) throw ...` guard clause on
//      one method, because that service is overwhelmingly non-admin traffic
//      and a full security filter chain would be overkill for one endpoint.
//   3. THIS class — `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")`
//      directly on AdminScanController.getStats(), chosen deliberately
//      (Tech Lead decision, 2026-10-25) BECAUSE that controller is brand
//      new with no sibling manual-guard method to stay consistent with,
//      making it a clean place to learn the annotation-based alternative —
//      not because one of the first two approaches was wrong.
//
// Unlike admin-service's own SecurityConfig, the filter-chain's own
// authorizeHttpRequests rule below stays permitAll() for every path — this
// is the key difference from option 1 above. Authorization for this one
// endpoint happens entirely via the @PreAuthorize annotation evaluated at
// the point of call, not via a path-matcher rule evaluated by the filter
// chain. Every other discovery-service endpoint (/scan/*, /actuator/health)
// is therefore completely unaffected by this ticket: nothing here changes
// what they accept.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DiscoveryAdminRoleAuthenticationFilter discoveryAdminRoleAuthenticationFilter;
    private final DiscoveryAuthenticationEntryPoint discoveryAuthenticationEntryPoint;
    private final DiscoveryAccessDeniedHandler discoveryAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF defends against a browser silently replaying a
                // logged-in user's session cookie against a forged form
                // submission. There are no cookies and no browser forms in
                // this topology — every caller is the API Gateway forwarding
                // an already-authenticated request service-to-service — so
                // disabling CSRF removes protection against a threat that
                // cannot occur here, same reasoning as admin-service's own
                // SecurityConfig.
                .csrf(csrf -> csrf.disable())
                // Stateless (Database per Service / no shared session
                // store): every request already carries its own identity via
                // the X-User-Role header the gateway set, so there is
                // nothing an HttpSession would usefully cache between
                // requests.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Deliberately permitAll() for every path, unlike
                // admin-service's `.anyRequest().hasRole("ADMIN")` — the
                // filter chain's own authorization rule plays NO gating role
                // in this service. @PreAuthorize on
                // AdminScanController.getStats() is the only place "is this
                // caller an admin" is actually enforced, so /scan/start,
                // /scan/nearby, /scan/stop, /scan/location and
                // /actuator/health all stay reachable with no role check,
                // exactly as they were before this ticket.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Runs DiscoveryAdminRoleAuthenticationFilter before Spring
                // Security's own username/password filter slot so
                // SecurityContext is already populated (or deliberately left
                // empty) by the time @PreAuthorize evaluates hasRole("ADMIN")
                // on whichever method is actually annotated.
                .addFilterBefore(discoveryAdminRoleAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(discoveryAuthenticationEntryPoint)
                        .accessDeniedHandler(discoveryAccessDeniedHandler));

        return http.build();
    }
}
