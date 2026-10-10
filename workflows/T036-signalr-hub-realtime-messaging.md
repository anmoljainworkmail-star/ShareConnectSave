# T036 — SignalR Hub + Real-time Messaging

Quick-glance flow diagram — what calls what, in what order, where it branches. Not
documentation; see `.claude/tickets/T036.md` and code comments for the "why."

```mermaid
flowchart TD
    A[Client opens WS /hubs/chat] --> B[SignalR assigns ConnectionId]
    B --> C{Client invokes}

    C -->|JoinRoom chatId| D[TryGetCallerUserId]
    D -->|missing/invalid| E[HubException: no identity]
    D -->|resolved| F[IsParticipantAsync]
    F -->|false| G[HubException: not participant]
    F -->|true| H[Groups.AddToGroupAsync]
    H --> I[conn added to chat:chatId]

    C -->|SendMessage chatId,content| J[TryGetCallerUserId]
    J -->|missing/invalid| E
    J -->|resolved| K[ChatService.SendMessageAsync]

    K --> L{content empty?}
    L -->|yes| M[InvalidRequest]
    L -->|no| N{chatId well-formed?}
    N -->|no| O[ChatRoomNotFound]
    N -->|yes| P[FindByIdAsync]
    P -->|null| O
    P -->|found| Q{sender is participant?}
    Q -->|no| R[NotAParticipant]
    Q -->|yes| S{room closed?}
    S -->|yes| T[ChatRoomClosed]
    S -->|no| U[CreateAsync - persist]
    U --> V[Success + MessageDto]

    M --> W[HubException to caller]
    O --> W
    R --> W
    T --> W

    V --> X[Clients.Group.SendAsync ReceiveMessage]
    X --> Y[Redis DB1 pub/sub relay]
    Y --> Z[Each replica: local group lookup]
    Z --> AA[Delivered to group members' sockets]
```

Upstream of node A, nothing yet creates the `chat_room` document itself — that's T038
(Kafka Consumer: connection.accepted), not implemented as of this diagram; every path
above assumes a `chatId` that already exists.
