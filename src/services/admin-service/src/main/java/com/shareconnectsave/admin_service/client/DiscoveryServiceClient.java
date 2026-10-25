package com.shareconnectsave.admin_service.client;

// Dependency Inversion (SOLID-D) + Interface Segregation (SOLID-I) — same
// shape as UserServiceClient's/RatingServiceClient's own class comments.
// admin-service's first-ever call into Discovery Service, and this
// interface exposes exactly the ONE call this ticket needs — not a
// god-client anticipating future Discovery Service calls admin-service
// doesn't make yet.
public interface DiscoveryServiceClient {

    // T106 — backed by Discovery Service's new GET /admin/scan/stats,
    // guarded on that side by @PreAuthorize("hasRole('ADMIN')") reading the
    // same X-User-Role header this client sends per-call (see
    // DiscoveryServiceClientImpl). Same "NO fallback, this IS the whole
    // data source" reasoning as UserServiceClient.getStats() (T105): the
    // Admin Dashboard platform-health tile has nothing to degrade into if
    // Discovery Service cannot be reached, so a fabricated zero would
    // misinform an admin rather than visibly fail.
    PlatformScanStats getStats();

    // Narrow, single-purpose DTO — same "no outward-facing contract drift"
    // reasoning as UserServiceClient.PlatformStats: Discovery Service's wire
    // shape can evolve without admin-service's own outward contracts moving
    // in lockstep with it.
    record PlatformScanStats(long totalScansToday) {
    }
}
