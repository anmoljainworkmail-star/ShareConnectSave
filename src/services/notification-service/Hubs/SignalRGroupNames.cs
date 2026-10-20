namespace notification_service.Hubs;

// Single source of truth for this service's group-naming scheme - kept
// identical to chat-service's SignalRGroupNames.User(userId) ("user:{id}")
// on purpose, per this ticket's instruction not to invent a different
// convention. Both hubs share one Redis backplane (DB 1); if the two
// services computed "user:{id}" differently there would be nothing wrong
// at compile time, but a Kafka consumer in this service could never push a
// notification that reliably lands in the same group ChatHub's
// "ChatOpened" push already uses on the chat-service side for the same
// user. Pulling it into its own static class (rather than inlining the
// string in NotificationHub) means a future rename only ever touches this
// one file, same reasoning as chat-service's version.
public static class SignalRGroupNames
{
    public static string User(long userId) => $"user:{userId}";
}
