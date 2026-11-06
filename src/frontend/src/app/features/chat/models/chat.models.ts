// Wire DTOs for chat-service (reached through the gateway at
// `${environment.apiBase}/chat/...` -- api-gateway's "chat-route" strips the
// "/chat" prefix, so the hub is `/chat/hubs/chat` and REST is `/chat/chats/*`).
// Names/casing are copied from the C# records (read, not guessed), the same
// discipline connection.models.ts and radar.models.ts follow.

// Mirrors chat-service's MessageDto.cs -- the SAME shape is the payload of the
// hub's "ReceiveMessage" push, so one interface covers the live feed.
export interface ChatMessage {
  id: string;
  chat_id: string;
  sender_id: number;
  content: string;
  // ISO-8601 (System.Text.Json writes DateTime that way).
  sent_at: string;
}

// Mirrors ChatOpenedNotification.cs -- pushed by ConnectionAcceptedHandler to
// each participant's personal group the moment the room exists. It is the
// only place the client learns the Mongo chat id for a connection.
export interface ChatOpenedNotification {
  chat_id: string;
  connection_id: string;
}

// Mirrors Events/ChatClosedReason.cs (and chat.closed.schema.json's enum).
export type ChatClosedReason = 'met_successfully' | 'timeout' | 'admin_closed';

// Mirrors ChatRoomLookupResponse.cs (GET /chats/by-connection/:id). status is
// the room's CURRENT state, so a client can tell a live room from one that
// closed while it was offline. closed_reason is null until the room is CLOSED.
export interface ChatRoomLookup {
  chat_id: string;
  connection_id: string;
  status: 'OPEN' | 'CLOSING' | 'CLOSED';
  closed_reason: ChatClosedReason | null;
}

// Mirrors ChatClosedNotification.cs -- CloseChatRoomService pushes it to both
// participants' personal groups after a room closes. Fields other than chat_id
// stay optional so the client tolerates a minimal payload.
export interface ChatClosedNotification {
  chat_id: string;
  connection_id?: string;
  closed_reason?: ChatClosedReason;
}

// Mirrors MarkMetSuccessfullyResponse.cs (POST /chats/:id/met).
export interface MarkMetResponse {
  chat_id: string;
  both_users_met: boolean;
}

// Hub connection lifecycle, same vocabulary NotificationService exposes.
export type ChatHubStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

// How a user arrived at the rating screen -- T069 reads this from navigation
// state to decide whether "Skip" is allowed (timeout path only).
export type RatingEntryPath = 'met' | 'timeout';

// One row of the rendered conversation. A discriminated union (kind) so the
// template can switch on it and TypeScript knows which fields exist -- cheaper
// and safer than three parallel arrays that must be kept in sync by index.
export type ChatTimelineItem =
  | { kind: 'system'; key: string; text: string }
  | { kind: 'timestamp'; key: string; at: string }
  | { kind: 'message'; key: string; message: ChatMessage };

// The two people in the room, as the header needs them.
export interface ChatParticipants {
  myUserId: number;
  myName: string;
  otherName: string;
  otherPhotoUrl: string | null;
}
