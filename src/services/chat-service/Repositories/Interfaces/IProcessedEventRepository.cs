namespace chat_service.Repositories.Interfaces;

// Pattern: Repository - hides "processed Kafka event ids live in a Mongo
// collection called processed_events" behind the same two-method shape
// every Java consumer in this codebase already uses via
// ProcessedEventsRepository.existsById/save (see
// connection-service/.../kafka/ProcessedEventsRepository.java). A consumer
// handler only ever needs to ask "have I seen this event_id" and "remember
// that I have now seen it" - it never builds a Mongo filter itself.
//
// Pattern: Interface Segregation (SOLID I) - kept separate from
// IChatRoomRepository/IMessageRepository for the same reason those two are
// kept separate from each other: ConnectionAcceptedHandler depends on this
// interface AND IChatRoomService, never on a combined "do everything"
// repository.
//
// Pattern: Dependency Inversion (SOLID D) - Program.cs is the only place
// that binds this interface to ProcessedEventRepository (the concrete Mongo
// implementation); ConnectionAcceptedHandler depends on the interface only,
// so an in-memory fake can stand in for unit tests without touching Mongo.
public interface IProcessedEventRepository
{
    Task<bool> ExistsAsync(string eventId);

    Task RecordAsync(string eventId);
}
