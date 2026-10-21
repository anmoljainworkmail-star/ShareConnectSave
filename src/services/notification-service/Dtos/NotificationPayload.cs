namespace notification_service.Dtos;

using System.Text.Json.Serialization;

// Wire shape for every SignalR push NotificationDispatchService sends — one
// shape shared by RequestReceived/RequestAccepted/RequestExpired rather than
// three separate payload types, since all three are structurally identical
// (title/body/data). Mirrors chat-service's ChatOpenedNotification in spirit
// (a small, purpose-built DTO for one hub push), just general enough to
// cover all three of this service's own event names instead of one.
public record NotificationPayload(
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("body")] string Body,
    [property: JsonPropertyName("data")] IDictionary<string, string> Data);
