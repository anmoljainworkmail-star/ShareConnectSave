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

    // KNOWN GAP (flagged, same precedent as discovery-service's
    // UserServiceClient.getBlockList comment on a documented-but-unbuilt User
    // Service endpoint): contracts/openapi/user-service.yaml's Profile.status
    // enum is [looking_for_companion, not_available] — a DISCOVERY-AVAILABILITY
    // toggle, a completely different concept from the suspended/active
    // ACCOUNT status SPECS.md's T057 spec means ("status = suspended in User
    // Service"). User Service has no endpoint or field for that account
    // status today (T059 "Suspend User" — the ticket that would first SET
    // it — has not shipped yet either). This method is written against the
    // endpoint User Service SHOULD expose for this ticket's own spec to be
    // satisfiable at all: GET /users?accountStatus=suspended, returning a
    // NEW field (accountStatus, deliberately NOT named "status" to avoid
    // colliding with Profile.status's existing, unrelated meaning). Until
    // that endpoint exists, this call 404s/connection-refuses on every
    // invocation — see UserServiceClientImpl's class comment for why this
    // method has NO @CircuitBreaker fallback and what that means for
    // GET /admin/queue today.
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
