package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// T106 — kept SEPARATE from ScanController rather than added as a method on
// it, same reasoning connection-service's AdminConnectionController (T104)
// documents for its own split from ConnectionController: this endpoint's
// trust boundary is different in KIND, not degree. Every ScanController
// method trusts "X-User-Id is a genuine, authenticated caller acting on
// their own scan session" — this one instead requires "the caller is a
// platform admin asking a platform-wide question with no per-user scope at
// all." The URL (/admin/scan/stats) also doesn't nest under plain /scan, so
// a dedicated class needs its own @RequestMapping regardless.
//
// Pattern: Method Security (@PreAuthorize) — this is the first controller
// method in this service (and the whole platform) to answer "is this caller
// an admin" via a Spring Security annotation evaluated AT THE POINT OF CALL,
// rather than admin-service's T056 path-matcher SecurityFilterChain rule or
// connection-service's T104/T107 manual `@RequestHeader` guard clause — see
// security.SecurityConfig's own class comment for the full three-way
// comparison. hasRole("ADMIN") checks the current thread's Authentication
// for a ROLE_ADMIN GrantedAuthority — the one
// DiscoveryAdminRoleAuthenticationFilter populates only when X-User-Role
// equals "admin" (Authentication), never whether that's enough
// (Authorization) — this annotation is the separate piece that decides that.
// A missing/wrong role never gets granted ROLE_ADMIN there, so this check
// fails and Spring Security routes the rejection to
// DiscoveryAuthenticationEntryPoint, which writes this project's shared
// {code, message, traceId} error envelope — never Spring Security's bare
// default 403 body.
//
// Dependency Inversion (SOLID-D): depends on the ScanStatsService interface,
// never ScanStatsServiceImpl — same convention as ScanController depending
// on ScanSessionService/ScanQueryService.
@RestController
@RequestMapping("/admin/scan")
@RequiredArgsConstructor
public class AdminScanController {

    private final ScanStatsService scanStatsService;

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScanStatsResponse> getStats() {
        return ResponseEntity.ok(scanStatsService.getStats());
    }
}
