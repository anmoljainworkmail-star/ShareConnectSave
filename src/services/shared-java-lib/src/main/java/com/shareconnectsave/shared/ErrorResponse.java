package com.shareconnectsave.shared;

// Pattern: Polyglot contract — this record and the C# record at
// services/user-service/Contracts/ErrorResponse.cs serialize to the identical JSON
// shape defined once in contracts/openapi/error-envelope.yaml. Neither language's
// type is the source of truth for the wire format; the YAML is, and both records are
// derived from it independently. A Java record's component names already serialize as
// plain lower-camelCase field names via Jackson with zero configuration, which is why
// this type needs no annotations to match the contract — unlike the C# side, whose
// serializer defaults to PascalCase and therefore needs [JsonPropertyName] to match.
public record ErrorResponse(String code, String message, String traceId) {
}
