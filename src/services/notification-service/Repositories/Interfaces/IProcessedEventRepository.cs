namespace notification_service.Repositories.Interfaces;

// Pattern: Repository — hides "where a processed Kafka event_id actually
// lives" behind the same two-method shape chat-service's Mongo-backed
// version already uses (see that interface's own comment for the full
// Repository/Interface Segregation/Dependency Inversion reasoning). Copied
// deliberately IDENTICAL to chat-service's interface — same method names,
// same signatures — even though the implementation underneath is Redis, not
// Mongo (notification-service has no database at all; see this ticket's own
// "Redis decision" note). A consumer handler only ever needs to ask "have I
// seen this event_id" and "remember that I have now seen it"; it must never
// know or care which storage technology answers that question.
//
// Pattern: Liskov Substitution (SOLID L) — any implementation of this
// interface (this Redis one, chat-service's Mongo one, a future in-memory
// test fake) MUST behave identically from a caller's point of view: "have I
// processed this event before" / "I have now processed it". A caller that
// only depends on this interface can never be surprised by which concrete
// class answered it.
public interface IProcessedEventRepository
{
    Task<bool> ExistsAsync(string eventId);

    Task RecordAsync(string eventId);
}
