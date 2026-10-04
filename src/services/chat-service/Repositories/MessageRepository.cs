using chat_service.Entities;
using chat_service.Repositories.Interfaces;
using MongoDB.Driver;

namespace chat_service.Repositories;

// Concrete Mongo implementation of IMessageRepository - see that interface
// for the Repository/Interface Segregation reasoning. CRUD only; the TTL
// enforcement that makes messages ephemeral lives entirely in
// MongoIndexInitializer, not here (Single Responsibility - this class
// persists messages, it does not own schema/index setup).
public sealed class MessageRepository : IMessageRepository
{
    private readonly IMongoCollection<MessageEntity> _messages;

    public MessageRepository(IMongoDatabase database)
    {
        // Literal "messages" - the same collection name T007's mongo-init
        // script and MongoIndexInitializer both reference. One name, three
        // places that must agree; this is the repository's half of that.
        _messages = database.GetCollection<MessageEntity>("messages");
    }

    public async Task CreateAsync(MessageEntity message)
    {
        await _messages.InsertOneAsync(message);
    }
}
