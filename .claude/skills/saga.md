# Saga + Distributed Transactions Skill — ShareConnectSave

You are implementing distributed transaction patterns in ShareConnectSave. This is a learning project — explain the concept when the code applies a non-obvious principle.

## Why distributed transactions are hard

The Problem: when a user accepts a connection, three things must happen atomically: Connection Service marks the request as ACCEPTED, Chat Service opens a chat room, and Notification Service fires a push notification. But these are three separate services with three separate databases. There's no shared transaction.

If Connection marks ACCEPTED but Chat fails to open the room, the user sees "accepted" but no chat. If Chat opens but the connection record is still PENDING, chat opens for no reason.

Two-phase commit (2PC) solves this at the database level but requires a coordinator, is slow, and doesn't work across services with different databases. We don't use it.

## Choreography Saga — how we solve it

A Saga breaks the distributed transaction into a sequence of local transactions. Each step is a single service doing one atomic thing and publishing an event. The next service reacts to that event.

There is no central coordinator. Each service knows what to do when it sees a specific event. This is called choreography — like a dance where each dancer knows their cue, no director needed.

If a step fails, the service publishes a different event (or uses a timeout) to trigger compensating transactions — actions that undo the previous steps.

## ConnectionLifecycleSaga — the main saga in this project

```
Step 1: User accepts → Connection Service updates to ACCEPTED → publishes connection.accepted
Step 2: Chat Service receives connection.accepted → opens chat room → (normal path continues)
        FAILURE: Chat Service fails to open room → publishes connection.chat-failed
Step 2 compensation: Connection Service receives connection.chat-failed → reverts to PENDING
Step 3: Users chat → either: Met Successfully or 2h auto-close
Step 4: Chat Service closes room → publishes chat.closed
Step 5: Rating Service receives chat.closed → creates rating prompts
Step 6: Users submit ratings → rating.submitted events
Step 7: Rating Service recalculates trust score → publishes trust.score.updated
Step 8: Discovery Service receives trust.score.updated → updates visibility/limits
```

The saga_id is the `connection_id` — it flows through every event as the natural key.

## saga_state table — tracking where each saga is

Each participating service writes to its own `saga_state` table. This is the paper trail.

```sql
CREATE TABLE saga_state (
  saga_id       UNIQUEIDENTIFIER NOT NULL,   -- connection_id
  saga_type     VARCHAR(64)      NOT NULL,   -- 'ConnectionLifecycle'
  current_step  VARCHAR(64)      NOT NULL,   -- 'ACCEPTED', 'CHAT_OPEN', 'CHAT_CLOSED', etc.
  status        VARCHAR(32)      NOT NULL,   -- 'in_progress' | 'completed' | 'compensating' | 'failed'
  context       NVARCHAR(MAX),               -- JSON: snapshot of relevant IDs for this step
  started_at    DATETIME2        NOT NULL,
  updated_at    DATETIME2        NOT NULL
);
```

### Java — updating saga state inside a consumer

```java
// Pattern: Saga state write in same transaction as business logic
// Reason: if business logic commits but saga_state update fails (or vice versa),
//         the saga loses track of where it is — state must be consistent with data
@Transactional
public void onConnectionAccepted(ConnectionAcceptedEvent event) {
  if (processedEventRepo.existsById(event.getEventId())) return;

  chatRoomRepository.openRoom(event.getConnectionId(), event.getRequesterId(), event.getRecipientId());

  // Update saga: this service's step is now complete
  sagaStateRepository.save(SagaState.builder()
    .sagaId(event.getConnectionId())
    .sagaType("ConnectionLifecycle")
    .currentStep("CHAT_OPEN")
    .status("in_progress")
    .context(toJson(event))
    .updatedAt(Instant.now())
    .build());

  processedEventRepo.save(new ProcessedEvent(event.getEventId(), Instant.now()));
}
```

### Compensating transaction — how Connection Service reverts

```java
// Pattern: compensating transaction — the undo for a saga step
// Reason: there's no rollback across services; compensation is an explicit business operation
@KafkaListener(topics = "connection.chat-failed")
@Transactional
public void onChatFailed(ConnectionChatFailedEvent event) {
  if (processedEventRepo.existsById(event.getEventId())) return;

  // Compensation: revert to PENDING so the recipient can accept again
  connectionRepository.updateStatus(event.getConnectionId(), Status.PENDING);

  sagaStateRepository.updateStep(event.getConnectionId(), "COMPENSATED_CHAT_OPEN", "compensating");
  processedEventRepo.save(new ProcessedEvent(event.getEventId(), Instant.now()));
}
```

## Saga timeouts — what if Chat never publishes connection.chat-failed?

Connection Service has a scheduled job that checks for accepted connections with no corresponding chat room after 5 minutes. If found, it self-compensates.

```java
// Pattern: saga timeout — guards against silent failures where no compensating event is published
@Scheduled(fixedDelay = 60_000)  // check every minute
public void detectStuckSagas() {
  Instant threshold = Instant.now().minus(5, ChronoUnit.MINUTES);
  List<SagaState> stuck = sagaStateRepository
    .findByCurrentStepAndStatusAndUpdatedAtBefore("ACCEPTED", "in_progress", threshold);

  for (SagaState saga : stuck) {
    // No chat opened in 5 minutes = treat as failed
    connectionRepository.updateStatus(saga.getSagaId(), Status.PENDING);
    saga.setStatus("compensating");
    saga.setCurrentStep("TIMED_OUT_CHAT_OPEN");
    sagaStateRepository.save(saga);
  }
}
```

## UserOnboardingSaga — append-only, no compensation

This saga is simpler — no step can be undone. If phone OTP fails, the user re-attempts. If identity verification fails, the user retries. Each step is independently retryable.

```
Google auth → phone verified → profile complete → user.verified published
```

No saga_state table needed — the `users` table columns themselves track state: `phone_verified`, `profile_complete`, `identity_verified`.

## ReportEscalationSaga — threshold-based escalation

```
report.filed received → count reports for target → if count ≥ threshold → recalculate trust → update discovery
```

The threshold (default: 3 reports from different reporters within 48h) triggers escalation. No compensation — reports cannot be un-filed.

## Key learning points

**Why choreography over orchestration:** An orchestrator (like Temporal, AWS Step Functions) is a central service that tells each participant what to do. With choreography, services are decoupled — adding a new service that reacts to `chat.closed` (e.g., an analytics service) requires no changes to existing services. The new service just subscribes to the topic.

**The cost of choreography:** harder to visualize the full saga state. The `saga_state` table per service partially compensates for this. In production, you'd also add distributed tracing (our Jaeger setup covers this — every event carries a `traceparent` that ties the whole saga together in one trace).

**Idempotency is mandatory in sagas:** a compensating transaction may be delivered twice. The same idempotency check on `event_id` protects compensating consumers too — not just the happy path.
