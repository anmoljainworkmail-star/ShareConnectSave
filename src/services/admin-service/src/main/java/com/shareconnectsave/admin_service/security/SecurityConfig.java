package com.shareconnectsave.admin_service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Pattern: centralized filter chain — this is the project's first real
// Spring Security usage. Every other Java service (Discovery, Connection,
// Rating, Report) answers "does this request belong to the user making it"
// with an inline @RequestHeader guard clause copy-pasted onto each
// controller method — fine for that narrow question, and deliberately kept
// that way elsewhere (per the java-spring-boot skill) because a full
// security filter chain would be overkill for it. Admin Service asks a
// different, higher-stakes question — "can this request suspend an account
// or override a trust score" — where the cost of getting that wrong (a
// missed guard clause on one new endpoint, six months from now) justifies
// building one gatekeeper that every request passes through, instead of N
// copies of the same check that can silently drift apart.
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AdminRoleAuthenticationFilter adminRoleAuthenticationFilter;
    private final AdminAuthenticationEntryPoint adminAuthenticationEntryPoint;
    private final AdminAccessDeniedHandler adminAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF defends against a browser silently replaying a logged-in
                // user's session cookie against a forged form submission. There
                // are no cookies and no browser forms in this topology — every
                // caller is the API Gateway forwarding an already-authenticated
                // request service-to-service — so disabling CSRF removes
                // protection against a threat that cannot occur here, rather
                // than genuinely weakening anything.
                .csrf(csrf -> csrf.disable())
                // Stateless (Database per Service / no shared session store):
                // every request already carries its own identity via the
                // X-User-Role header the gateway set, so there is nothing an
                // HttpSession would usefully cache between requests — and a
                // session would be one more piece of state to keep consistent
                // the moment this service is horizontally scaled.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Order matters: authorizeHttpRequests evaluates rules in
                        // registration order and stops at the first match. This
                        // permitAll() for the health check MUST be registered
                        // before the anyRequest() catch-all below, or container
                        // orchestration's liveness probe — which sends no
                        // X-User-Role header at all — would be rejected the same
                        // as any other unauthenticated caller.
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                // Runs AdminRoleAuthenticationFilter before Spring Security's own
                // username/password filter slot so SecurityContext is already
                // populated (or deliberately left empty) by the time
                // authorizeHttpRequests' hasRole("ADMIN") rule is evaluated.
                .addFilterBefore(adminRoleAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(adminAuthenticationEntryPoint)
                        .accessDeniedHandler(adminAccessDeniedHandler));

        return http.build();
    }
}
