namespace chat_service.Entities;

// Open/Closed Principle (SOLID O), applied to a small state vocabulary: these
// three values ARE the chat room state machine's alphabet (OPEN -> CLOSING ->
// CLOSED, see T037 ticket). Centralizing them here means every class that
// reads or writes ChatRoomEntity.Status (ChatRoomRepository,
// CloseChatRoomService, ChatLifecycleService, ChatAutoCloseHostedService)
// compares against the same member, instead of five files each hand-typing
// "OPEN"/"CLOSED" and risking a typo that silently never matches - and unlike
// a hand-typed string literal, a typo'd enum member (ChatRoomStatus.Opne)
// fails to COMPILE rather than silently never matching at runtime.
//
// A real C# enum, serialized as a string via ChatRoomEntity's
// [BsonRepresentation(BsonType.String)] attribute - not an opaque int (which
// would be unreadable in `mongosh`/Compass) and not a hand-rolled BSON
// serializer convention. [BsonRepresentation(BsonType.String)] is a single
// built-in MongoDB.Driver attribute that serializes an enum to its member
// name, so there is no extra plumbing to pay for compile-time safety here.
// Members are named in ALL CAPS so the wire format ("OPEN"/"CLOSING"/
// "CLOSED") is unchanged from this project's original plain-string design.
public enum ChatRoomStatus
{
    OPEN,
    CLOSING,
    CLOSED,
}
