# SignalR Redis Backplane — Multi-User, Multi-Pod Fan-out

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see `T036`'s workflow/ticket or the
conversation that produced this diagram.

```mermaid
flowchart TD
    subgraph U1[User A's browser/app - userId 1001]
        A1[Open WS to /hubs/chat]
        A2[invoke JoinRoom chatId]
        A3[invoke SendMessage chatId, hi]
        A4([sees ReceiveMessage - echo])
    end

    subgraph P1[Pod 1 - happens to hold A's socket]
        B1[Context.ConnectionId = conn-A]
        B2[local group map:<br/>chat:abc123 to conn-A]
        B3[Clients.Group chat:abc123<br/>.SendAsync ReceiveMessage]
        B4[Pod 1 also receives<br/>its own publish]
        B5{conn-A in<br/>local map?}
        B6[deliver over conn-A socket]
    end

    subgraph R[Redis DB 1 - pub/sub channel only]
        RX[publish: deliver to group chat:abc123]
    end

    subgraph U2[User B's browser/app - userId 1002]
        C1[Open WS to /hubs/chat]
        C2[invoke JoinRoom chatId]
        C3([ReceiveMessage fires])
    end

    subgraph P2[Pod 2 - happens to hold B's socket]
        D1[Context.ConnectionId = conn-B]
        D2[local group map:<br/>chat:abc123 to conn-B]
        D3[Pod 2 receives<br/>the published message]
        D4{conn-B in<br/>local map?}
        D5[deliver over conn-B socket]
    end

    A1 --> B1
    A2 --> B2
    C1 --> D1
    C2 --> D2

    A3 --> B3
    B3 --> RX
    RX -->|every subscriber gets it| B4
    RX -->|every subscriber gets it| D3

    B4 --> B5
    B5 -->|yes| B6
    B6 --> A4

    D3 --> D4
    D4 -->|yes| D5
    D5 --> C3
```

Redis never stores WHO is in a group — it only relays "something was sent to group
chat:abc123" to every pod; each pod decides for itself, from its own local in-memory map,
whether it holds any sockets that belong in that group.
