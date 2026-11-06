// Wire DTOs for connection-service's /connections endpoints (reached through
// the gateway at `${environment.apiBase}/connection/connections/*` --
// api-gateway's "connection-route" strips the "/connection" prefix) and for
// notification-service's SignalR pushes. Names/casing are copied from the
// Java records and the C# NotificationPayload directly (read, not guessed),
// the same discipline radar.models.ts and auth.models.ts follow.

// Mirrors CreateConnectionDto.java.
export interface CreateConnectionRequest {
  recipient_id: number;
}

// Mirrors ConnectionCreatedResponse.java (POST /connections -> 201).
export interface ConnectionCreatedResponse {
  connection_id: number;
}

// Mirrors the Java ConnectionStatus enum's values as they serialize.
export type ConnectionStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED';

// Mirrors ConnectionResponse.java (accept/decline/pending/active). The two
// Instants serialize as ISO-8601 strings under Spring Boot's default Jackson
// setup (WRITE_DATES_AS_TIMESTAMPS is off by default in Boot).
export interface ConnectionResponse {
  id: number;
  requester_id: number;
  recipient_id: number;
  status: ConnectionStatus;
  created_at: string;
  expires_at: string;
}

// Mirrors notification-service's NotificationPayload.cs -- one shape for
// every push. `data` only ever carries `connectionId` today (see the
// Connection*Handler classes): there is NO sender identity in the push, which
// is why the inbound-request screen re-fetches GET /connections/pending
// instead of trusting the event payload to describe who is asking.
export interface NotificationPayload {
  title: string;
  body: string;
  data: Record<string, string>;
}

// Event names, as registered by the server. Open/Closed (O): NotificationService
// iterates this list to subscribe, so a new event is one new entry here, not a
// new hand-written .on() call.
//
// Deliberate deviation from T067's ticket text, found by reading
// notification-service instead of guessing: the server's "new request" event
// is named RequestReceived (ConnectionRequestedHandler), not
// "ConnectionRequest". And NO decline event exists server-side
// (ConnectionServiceImpl.declineConnection writes no outbox row and there is
// no connection.declined topic), so RequestDeclined is registered here
// forward-compatibly but will not fire until a backend ticket adds it. See
// .claude/notes/follow-ups.md "From T067".
export const NOTIFICATION_EVENT_NAMES = [
  'RequestReceived',
  'RequestAccepted',
  'RequestDeclined',
  'RequestExpired'
] as const;

export type NotificationEventName = (typeof NOTIFICATION_EVENT_NAMES)[number];

export interface NotificationEvent {
  name: NotificationEventName;
  payload: NotificationPayload;
}

// Lifecycle of the hub connection, exposed so a future UI (Chat's
// "Reconnecting..." amber bar, FIGMA_SPEC Screen 12) can reflect it.
export type HubConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
