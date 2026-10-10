using System.Text.Json.Serialization;

namespace chat_service.Contracts;

// Pattern: Polyglot contract, enforced explicitly rather than by convention
// (CLAUDE.md's non-negotiable error envelope rule: { code, message, traceId }
// on every error, across every service regardless of language/framework).
// Copied from user-service's Contracts/ErrorResponse.cs byte-for-byte -
// same reasoning as that file's own comment: this is the canonical shape,
// copied (not referenced across a service boundary - Database/Service-per-
// service independence applies to code sharing too) into each service.
//
// This is chat-service's FIRST controller/endpoint ticket (T035 was Mongo
// setup only, no HTTP surface) - this file did not need to exist until now.
public record ErrorResponse(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("traceId")] string TraceId
);
