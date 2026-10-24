package com.shareconnectsave.connection.domain;

// T104 — PUT /admin/connections/users/{userId}/expire-active's response
// body. Deliberately plain "expiredCount" (camelCase, no @JsonProperty),
// NOT the snake_case convention ConnectionResponse/ConnectionCreatedResponse
// use elsewhere in this service: those two shapes are mirrored in
// /contracts/kafka or a cross-service contract the API Gateway's own
// consumers rely on staying snake_case; this one is a brand-new, ticket-
// specified shape (`{ "expiredCount": N }`) with exactly one caller
// (admin-service's T059 suspend flow) and no existing convention to match,
// so it's left matching the record component name as-is.
//
// A bare count, not the list of expired connections themselves (see this
// ticket's "What NOT to do"): admin-service's audit log only needs to know
// how many connections were cleared, never which ones — enumerating them
// here would leak Connection Service's own row ids across the service
// boundary for no reason (Database per Service: admin-service has no
// business holding onto another service's primary keys).
public record ExpireActiveConnectionsResponse(int expiredCount) {
}
