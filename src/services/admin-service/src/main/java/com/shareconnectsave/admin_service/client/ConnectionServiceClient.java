package com.shareconnectsave.admin_service.client;

// Dependency Inversion (SOLID-D): ReviewQueueServiceImpl.suspend depends on
// this interface, never on ConnectionServiceClientImpl or WebClient directly
// — same shape as UserServiceClient's/RatingServiceClient's own class
// comments. admin-service's first-ever call into Connection Service.
//
// Interface Segregation (SOLID-I): this interface exposes exactly the ONE
// call this ticket needs (expire a user's active connection requests) — not
// a god-client anticipating future Connection Service calls admin-service
// doesn't make yet. Same narrow-interface discipline UserServiceClient's own
// comment documents for its three methods.
public interface ConnectionServiceClient {

    // Backed by T104's PUT /admin/connections/users/{userId}/expire-active.
    // Returns the CONFIRMED expired count (never assumes the write succeeded
    // just because no exception was thrown) — same "write client returns the
    // upstream's own confirmed outcome" discipline as
    // UserServiceClient.setStatus.
    int expireActiveConnections(String userId);

    // T107 — backed by Connection Service's GET /admin/connections/stats.
    // Named getStats() to match the ticket spec and UserServiceClient's own
    // T105 precedent, even though UserServiceClient.getStats() returns a
    // PlatformStats record and this one returns a plain int: User Service's
    // getStats() already had TWO fields on the horizon when T105 shipped (a
    // record was the right call there), this one has exactly ONE — Admin
    // Dashboard needs a single platform-health number here, not a result set
    // to page through — so a bespoke single-field record would be ceremony
    // with no caller that benefits from it (Interface Segregation, SOLID-I:
    // this client exposes exactly what ReviewQueueServiceImpl needs, nothing
    // wrapped for a future that may never arrive).
    int getStats();
}
