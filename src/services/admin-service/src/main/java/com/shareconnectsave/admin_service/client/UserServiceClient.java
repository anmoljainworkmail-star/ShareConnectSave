package com.shareconnectsave.admin_service.client;

import java.time.Instant;
import java.util.List;

// Pattern: Dependency Inversion (SOLID-D) — ReviewQueueServiceImpl depends on
// this interface, never on UserServiceClientImpl or WebClient directly, same
// shape as discovery-service's own UserServiceClient. The concrete bean
// behind it can change (or be swapped for an in-memory fake in a future unit
// test) without ReviewQueueServiceImpl changing at all.
//
// Interface Segregation (SOLID-I): this interface only exposes the three
// calls admin-service actually needs from User Service (list suspended
// accounts, read one profile, flip status) — not a god-client reusing every
// method User Service happens to expose.
public interface UserServiceClient {

    // T102: backed by User Service's GET /users/internal?accountStatus=suspended
    // — an internal-only endpoint (Docker-network reachable only, same trust
    // boundary as its existing GET /users/internal/{id}/fcm-token), separate
    // from the public /users/{id} discovery-privacy-minimized endpoint.
    // accountStatus (Active/Suspended) is a NEW column on User Service's own
    // table, deliberately independent of contracts/openapi/user-service.yaml's
    // Profile.status enum ([looking_for_companion, not_available]) — that is
    // a DISCOVERY-AVAILABILITY toggle, a completely different concept from
    // this account-standing field, which is why the wire property is named
    // account_status, never status. See UserServiceClientImpl's class comment
    // for why this method still has NO @CircuitBreaker fallback even now that
    // the gap is closed: GET /admin/queue has no other data source to
    // approximate from if User Service cannot be reached.
    List<SuspendedUserSummary> getSuspendedUsers();

    // Returns null ONLY for a genuine 404 (user id does not exist) — same
    // "don't fabricate a placeholder for a truly missing id" contract as
    // discovery-service's UserServiceClient.getUserCard.
    UserProfileSummary getUser(String userId);

    // Synchronous write — User Service is the system of record for account
    // status (reinstate's spec step 1). Returns the confirmed new status so
    // ReviewQueueServiceImpl never has to assume the call succeeded just
    // because no exception was thrown.
    UserProfileSummary setStatus(String userId, String accountStatus);

    // Nested, shared-shape DTOs (same "no outward-facing contract drift"
    // reasoning as discovery-service's own nested PublicUserProfileResponse):
    // User Service's wire shape can evolve without ReviewQueueItem/
    // UserDetailResponse (admin-service's own outward contract) moving in
    // lockstep with it.
    record SuspendedUserSummary(String userId, String displayName, Instant suspendedAt) {
    }

    record UserProfileSummary(String userId, String displayName, String accountStatus, Instant suspendedAt) {
    }
}
