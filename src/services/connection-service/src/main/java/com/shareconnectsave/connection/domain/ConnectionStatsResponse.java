package com.shareconnectsave.connection.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// T107 — GET /admin/connections/stats' response body. Deliberately UNLIKE
// ExpireActiveConnectionsResponse (plain camelCase, no @JsonProperty): that
// record was free to pick its own wire shape because ITS ticket left the
// JSON key unspecified and it had exactly one caller with no existing
// contract to match (see that record's own comment). This ticket's spec
// fixes the wire key explicitly as `total_connections_accepted_today` — the
// same snake_case Admin Dashboard stats-tile convention User Service's
// InternalStatsResponse (`total_active_users`, T105) already established on
// the .NET side. @JsonProperty is what lets this record keep an idiomatic
// camelCase Java field while still serializing to that fixed wire contract
// exactly, rather than renaming the field itself to snake_case.
public record ConnectionStatsResponse(
        @JsonProperty("total_connections_accepted_today") int totalConnectionsAcceptedToday) {
}
