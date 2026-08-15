using System.Text.Json.Serialization;

namespace user_service.Contracts;

// Pattern: Polyglot contract, enforced explicitly rather than by convention.
// Reason: System.Text.Json's default naming policy is PascalCase (Code, Message,
// TraceId). The Java side of this contract (shared-java-lib's ErrorResponse record)
// serializes plain lower-camelCase field names with no naming policy involved at all.
// If this record relied on ambient JsonSerializerOptions to produce the right casing,
// a future change to Program.cs's serializer config (in this service or copied into
// another one) would silently break the wire shape for every client. [JsonPropertyName]
// pins the JSON keys to contracts/openapi/error-envelope.yaml regardless of whatever
// serializer settings this or any other .NET service configures.
//
// This is the canonical shape — copied (not referenced) into each other .NET service
// in their own tickets, per T003's scope.
public record ErrorResponse(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("traceId")] string TraceId
);
