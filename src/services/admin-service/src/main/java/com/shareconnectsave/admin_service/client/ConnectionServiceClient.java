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
}
