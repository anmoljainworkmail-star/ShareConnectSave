# Kafka + Outbox Pattern Skill — ShareConnectSave

You are working with Kafka and the Outbox Pattern in ShareConnectSave. This is a learning project — explain the concept in comments when the code applies a non-obvious principle.

## Why Kafka exists here

The Problem: when User Service verifies a user, Discovery Service needs to add them to the scan pool. Without Kafka, User Service would have to call Discovery Service synchronously — tight coupling. If Discovery is down, verification fails. If User is slow, Discovery waits.

Kafka's answer: User Service publishes an event ("user verified, here's the data") and moves on. Discovery Service subscribes and reacts whenever it processes the event. Neither knows the other exists. They can be deployed, scaled, or fail independently.

Events are named in the past tense — they are facts, not commands: `user.verified`, `chat.closed`, `rating.submitted`.

## Topics in this project

| Topic | Producer | Consumers |
|---|---|---|
| `user.verified` | User Service | Discovery Service |
| `connection.accepted` | Connection Service | Chat Service, Notification Service |
| `connection.expired` | Connection Service | Notification Service |
| `connection.chat-failed` | Chat Service | Connection Service (compensating) |
| `chat.closed` | Chat Service | Rating Service, Notification Service |
| `rating.submitted` | Rating Service | Rating Service (self — recalculate) |
| `trust.score.updated` | Rating Service | Discovery Service |
| `report.filed` | Report Service | Admin Service |

## JSON schema conventions

All messages are JSON with snake_case fields. Every message has `event_id` (UUID) — used for consumer deduplication.

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "connection_id": "...",
  "requester_id": "...",
  "recipient_id": "...",
  "accepted_at": "2024-01-15T10:30:00Z"
}
```

Schema files live in `/contracts/kafka/` — both .NET and Java deserialize against these.

## The Outbox Pattern — why it exists

The Problem: your service writes a row to the database, then tries to publish to Kafka. Between those two operations, the process can crash, the network can fail, or Kafka can be temporarily unavailable. Your DB has the business data but no one gets notified.

The Solution: in the same database transaction as your business record, write a second row to an `outbox` table. A background process polls the outbox table and publishes to Kafka. Once published, the row is marked processed.

Why this works: the DB transaction is atomic. Either both the business record and the outbox row commit, or neither does. The outbox relay only marks a row processed after Kafka acknowledges receipt. If it crashes between publish and mark, the row gets republished (Kafka consumers handle duplicates via idempotency).

### Java implementation

```java
// Entity
@Entity @Table(name = "outbox")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class OutboxEvent {
  @Id private UUID id;
  private String topic;
  private String payload;           // JSON string
  @Enumerated(EnumType.STRING)
  private OutboxStatus status;      // PENDING | PROCESSED
  private Instant createdAt;
}

// Service interface (Dependency Inversion — callers never know it's SQL-backed)
public interface IOutboxService {
  void publish(String topic, Object payload);
}

// Usage inside a @Transactional business method
@Transactional
public void acceptRequest(UUID connectionId) {
  connectionRepository.updateStatus(connectionId, Status.ACCEPTED);
  // Pattern: Outbox — payload written to DB in same tx; Kafka publish happens later
  outboxService.publish("connection.accepted", new ConnectionAcceptedEvent(connectionId, ...));
  // Both committed together — no split-brain possible
}

// Relay — runs every 500ms
@Scheduled(fixedDelay = 500)
@Transactional
public void relayPendingEvents() {
  List<OutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, 50));
  for (OutboxEvent event : pending) {
    kafkaTemplate.send(event.getTopic(), event.getPayload()).get();  // synchronous confirm
    event.setStatus(OutboxStatus.PROCESSED);
  }
}
```

### .NET implementation

```csharp
// Same concept, BackgroundService polls the outbox
// The shared library (T092) provides AddOutbox<TDbContext>() extension method
// to register the relay without repeating the pattern per service
builder.Services.AddOutbox<AppDbContext>();
```

## Consumer Idempotency — why it exists

The Problem: Kafka guarantees at-least-once delivery. This means in failure scenarios (consumer crash after processing but before committing the offset), the same message gets delivered again.

The Solution: every consumer maintains a `processed_events` table. Before processing, check if the event_id has been seen. If yes, skip. If no, process and record it.

### Java

```java
// Pattern: Idempotency — guards against at-least-once Kafka delivery
// Reason: without this check, a redelivered connection.accepted event opens two chat rooms
@KafkaListener(topics = "connection.accepted")
@Transactional
public void handle(ConnectionAcceptedEvent event) {
  if (processedEventRepo.existsById(event.getEventId())) return; // skip duplicate

  doBusinessLogic(event);
  processedEventRepo.save(new ProcessedEvent(event.getEventId(), Instant.now()));
}
```

### .NET

```csharp
public async Task HandleAsync(ConnectionAcceptedEvent evt, CancellationToken ct)
{
  if (await processedEvents.ExistsAsync(evt.EventId)) return;

  await businessLogic.ExecuteAsync(evt);
  await processedEvents.RecordAsync(evt.EventId);
}
```

### Pruning processed_events

The table grows indefinitely without pruning. A scheduled job deletes rows older than 7 days — safe because Kafka's message retention is also 7 days (any redelivery would happen within that window).

```java
@Scheduled(cron = "0 0 3 * * *")  // 3 AM daily
public void pruneOldEvents() {
  processedEventRepo.deleteByProcessedAtBefore(Instant.now().minus(7, ChronoUnit.DAYS));
}
```

## Dead Letter Queue (DLQ)

For the Report Service (T054), Kafka publish failures should not silently swallow the event. If the outbox relay fails to publish after 3 retries, the event goes to a DLQ topic (`report.filed.dlq`) for manual inspection.

```java
// DLQ pattern: failed messages park in a side topic rather than blocking the relay
ProducerRecord<String, String> dlq = new ProducerRecord<>("report.filed.dlq", payload);
kafkaTemplate.send(dlq);
event.setStatus(OutboxStatus.DLQ);  // mark as sent to DLQ, not processed
```

## Topic configuration (T009 init script)

```bash
kafka-topics.sh --bootstrap-server kafka:9092 --create \
  --topic user.verified \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000   # 7 days
```

Run this for all 8 topics. Partition count = 3 is enough for dev. In prod this would scale with consumer group size.
