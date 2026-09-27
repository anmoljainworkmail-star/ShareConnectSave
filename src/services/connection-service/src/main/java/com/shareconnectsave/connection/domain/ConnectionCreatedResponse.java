package com.shareconnectsave.connection.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// connectionId is the JPA entity's internal @Id, re-exposed under its own
// JSON name here rather than letting ConnectionRequest serialize straight
// onto the wire — same separation of "persistence shape" from "public
// contract" as discovery-service's ScanStartResponse.
public record ConnectionCreatedResponse(
        @JsonProperty("connection_id") Long connectionId
) {
}
