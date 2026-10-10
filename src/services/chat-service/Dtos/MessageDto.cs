namespace chat_service.Dtos;

using System.Text.Json.Serialization;
using chat_service.Entities;

// Polyglot contract (same reasoning as user-service's UserProfileDto):
// [JsonPropertyName] pins every wire key explicitly rather than trusting
// whatever ambient JsonSerializerOptions this process happens to run with -
// both this HTTP response (ChatController) and this SignalR broadcast
// payload (ChatHub's "ReceiveMessage" event) serialize the SAME DTO, so one
// explicit shape is what keeps the Angular client's history fetch and its
// real-time feed byte-for-byte consistent with each other.
//
// This is a response DTO, not the MessageEntity itself - callers (the
// Angular PWA) never need to know this is "really" a Mongo document with a
// BsonId; FromEntity below is the one place that projection happens.
public record MessageDto(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("sender_id")] long SenderId,
    [property: JsonPropertyName("content")] string Content,
    [property: JsonPropertyName("sent_at")] DateTime SentAt)
{
    public static MessageDto FromEntity(MessageEntity entity) => new(
        entity.Id!,
        entity.ChatId,
        entity.SenderId,
        entity.Content,
        entity.SentAt);
}
