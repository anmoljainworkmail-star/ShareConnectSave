namespace chat_service.Hubs;

// Single source of truth for this service's two SignalR group-naming
// schemes. Before T038, ChatHub was the ONLY class that ever needed a group
// name, so "chat:{id}" lived as a private method on that one class - T038
// adds a SECOND caller (Messaging/Handlers/ConnectionAcceptedHandler, which
// has no Hub instance to call a private method on) that needs the "user:{id}"
// scheme to broadcast "ChatOpened" to a client ChatHub itself joined to that
// group in OnConnectedAsync. Pulling both naming schemes out to one small
// static class - rather than each class hand-rolling its own
// $"user:{id}" string - is what guarantees a future rename of either scheme
// only ever needs to change this one file.
public static class SignalRGroupNames
{
    public static string Chat(string chatId) => $"chat:{chatId}";

    public static string User(long userId) => $"user:{userId}";
}
