# FCM Push Notification — End-to-End Delivery Flow

Quick-glance boxes-and-arrows reference for how a push notification actually travels from
our server to a phone. No prose — if you need the "why," see T047's ticket/review or
`.claude/notes/follow-ups.md`'s "From T047" entries.

```mermaid
flowchart TD
    subgraph BOOT["Program.cs — startup, once"]
        P1["Read FCM_CREDENTIALS_JSON"]
        P2["FirebaseApp.Create credential"]
        P3(["FirebaseApp.DefaultInstance<br/>shared singleton"])
        P1 --> P2 --> P3
    end

    subgraph DEV["User's phone"]
        D1["App installs / opens"]
        D2["OS registers with FCM"]
        D3(["Device token issued"])
        D4["App sends token to backend"]
        D9(["OS push listener<br/>always connected"])
        D10(["Notification shown<br/>app can be closed"])
        D1 --> D2 --> D3 --> D4
    end

    subgraph US["User Service"]
        U1{{"fcm_token column<br/>NOT YET BUILT"}}
        U2["GET /users/:id<br/>returns profile"]
    end

    D4 -.->|"not wired up yet"| U1

    subgraph NS["Notification Service"]
        N1["Business event fires<br/>e.g. connection.requested"]
        N2["FcmNotificationService.SendAsync"]
        N3["IUserServiceClient<br/>.GetFcmDeviceTokenAsync"]
        N4{"token returned?"}
        N5(["no-op — log + return"])
        N6["Build FirebaseAdmin Message"]
        N7["FirebaseMessaging.DefaultInstance<br/>.SendAsync(message)"]
        N1 --> N2 --> N3
    end

    N3 --> U2
    U1 -.->|"blocks real lookup today"| U2
    U2 --> N4
    N4 -->|"null — always, today"| N5
    N4 -->|"real token — once US ships it"| N6
    N6 --> N7
    P3 -.->|"shared credentials"| N7

    subgraph FB["Firebase backend (Google)"]
        F1["Authenticate request<br/>via service account"]
        F2{"token's platform?"}
        F3["Google push channel<br/>(Android)"]
        F4["Relay to Apple APNs<br/>(iOS)"]
        F5["Browser push service<br/>(Web)"]
        F1 --> F2
        F2 -->|"Android"| F3
        F2 -->|"iOS"| F4
        F2 -->|"Web"| F5
    end

    N7 --> F1
    F3 --> D9
    F4 --> D9
    F5 --> D9
    D9 --> D10
```

The server never holds a direct connection to the phone — it only ever talks to Firebase's
backend over plain HTTPS; the always-open connection is between the device's OS and its
platform's push service, maintained independently of whether our app is running.
