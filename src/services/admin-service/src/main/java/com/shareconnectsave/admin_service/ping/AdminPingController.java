package com.shareconnectsave.admin_service.ping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Trivial, deliberately feature-less endpoint whose only purpose is proving
// SecurityConfig's filter chain works end-to-end (T056 AC4) — no other
// admin-service controller exists yet. A request carrying
// X-User-Role: admin should reach this method and get 200; a request
// without it should never reach here at all, rejected upstream by
// AdminAuthenticationEntryPoint. No @RequestHeader("X-User-Role") guard
// clause appears here or in any future admin-service controller — that
// check is the filter chain's job alone (see SecurityConfig), and
// duplicating it here would be exactly the redundant, driftable copy this
// ticket's filter chain exists to avoid.
@RestController
public class AdminPingController {

    @GetMapping("/admin/ping")
    public String ping() {
        return "pong";
    }
}
