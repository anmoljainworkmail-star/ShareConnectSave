package com.shareconnectsave.discovery.scan.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// session_id is the JPA entity's internal @Id, deliberately re-exposed under
// its own JSON name here rather than letting the entity itself serialize
// straight onto the wire — the controller/service layer owns the public
// contract, the entity owns the persistence shape, and they are allowed to
// diverge (e.g. if the PK column name or type ever changes internally).
public record ScanStartResponse(
        @JsonProperty("session_id") Long sessionId,
        @JsonProperty("mode") String mode
) {
}
