# Java Spring Boot Skill — ShareConnectSave Backend Services

You are implementing a Java 21 / Spring Boot 3 microservice for ShareConnectSave. This is a learning project — add short comments naming the pattern (not what the code does) when it is non-obvious.

## Project structure (package-by-feature, not layer)

```
src/main/java/com/shareconnectsave/<service>/
  <feature>/
    <Feature>Controller.java
    <Feature>Service.java
    <Feature>Repository.java          ← interface extends JpaRepository
    domain/
      <Entity>.java                   ← @Entity, Lombok @Getter @Builder @NoArgsConstructor
      <Dto>.java                      ← record or Lombok @Value
    mapper/
      <Feature>Mapper.java            ← @Mapper(componentModel = "spring") MapStruct
  kafka/
    <Topic>Producer.java
    <Topic>Consumer.java
  exception/
    GlobalExceptionHandler.java       ← from shared-java-lib
  config/
    <Feature>Config.java
src/main/resources/
  db/migration/                       ← Flyway V1__<description>.sql
  application.yml
```

## Lombok — always use these annotations

```java
@Getter                 // generates getters (not @Data — avoids accidental equals/hashCode)
@Builder                // builder pattern for construction
@NoArgsConstructor      // JPA requires no-arg constructor
@AllArgsConstructor     // used with @Builder
@Slf4j                  // injects log field (private static final Logger log = ...)
```

Never use `@Data` on `@Entity` classes — it generates `equals`/`hashCode` based on all fields which breaks JPA lazy loading.

## MapStruct — DTO mapping (never write mappers by hand)

```java
// Pattern: MapStruct generates implementation at compile time — zero reflection overhead
// Reason: hand-written mappers are error-prone and unmaintainable as fields change
@Mapper(componentModel = "spring")
public interface ConnectionMapper {
  ConnectionResponse toResponse(ConnectionRequest request);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  ConnectionRequest toEntity(CreateConnectionDto dto);
}
```

## Flyway migrations

Name pattern: `V{version}__{description}.sql` — two underscores, snake_case description.

```sql
-- V1__create_connection_requests.sql
CREATE TABLE connection_requests (
  id           UUID          NOT NULL DEFAULT NEWID(),
  requester_id UUID          NOT NULL,
  recipient_id UUID          NOT NULL,
  status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  created_at   DATETIME2     NOT NULL DEFAULT GETUTCDATE(),
  expires_at   DATETIME2     NOT NULL,
  updated_at   DATETIME2     NOT NULL DEFAULT GETUTCDATE(),
  CONSTRAINT pk_connection_requests PRIMARY KEY (id)
);
CREATE INDEX idx_connection_requests_recipient ON connection_requests (recipient_id, status);
```

## Dependency Inversion — always inject interfaces

```java
// Pattern: Dependency Inversion (SOLID D)
// Reason: concrete class → can't swap implementation in tests or in Phase 14 outbox migration
@Service
@RequiredArgsConstructor
public class ConnectionService {
  private final IConnectionRepository connectionRepository; // interface, not SqlConnectionRepository
  private final IOutboxService outboxService;               // interface — implementations: SQL, in-memory test
}
```

## WebClient — outbound HTTP (not RestTemplate)

```java
// Pattern: reactive HTTP client with non-blocking I/O
// Reason: RestTemplate is deprecated in Spring 6; WebClient handles timeouts and retries cleanly
@Bean
public WebClient userServiceClient(WebClient.Builder builder) {
  return builder
    .baseUrl("http://user-service:8081")         // Docker container name — no hardcoded IP
    .defaultHeader("Content-Type", "application/json")
    .build();
}

// Usage: block() is acceptable for services that are not fully reactive
UserProfile profile = userServiceClient.get()
  .uri("/users/{id}", userId)
  .retrieve()
  .onStatus(HttpStatusCode::is4xxClientError, resp -> Mono.error(new UserNotFoundException(userId)))
  .bodyToMono(UserProfile.class)
  .block();
```

## Resilience4j — circuit breaker for external calls

```java
// Pattern: Circuit Breaker (system design principle)
// Reason: if User Service is down, Discovery Service keeps working with cached data rather than hanging
@CircuitBreaker(name = "userService", fallbackMethod = "getCachedProfile")
public UserProfile getUserProfile(UUID userId) {
  return userServiceClient.get().uri("/users/{id}", userId).retrieve()
    .bodyToMono(UserProfile.class).block();
}

private UserProfile getCachedProfile(UUID userId, Exception ex) {
  // Cache-Aside fallback: return stale data rather than failing the discovery query
  return redisCache.get("profile:" + userId);
}
```

application.yml circuit breaker config:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      userService:
        failure-rate-threshold: 50         # open after 50% failures
        wait-duration-in-open-state: 30s   # stay open 30s before half-open probe
        sliding-window-size: 10
```

## Spring Kafka — consumer pattern (always check idempotency)

```java
@KafkaListener(topics = "connection.accepted", groupId = "chat-service")
@Transactional
public void onConnectionAccepted(ConnectionAcceptedEvent event) {
  // Pattern: Idempotency check — Kafka delivers at-least-once
  // Reason: without this, a redelivered event opens a duplicate chat room
  if (processedEventRepository.existsById(event.getEventId())) return;

  openChatRoom(event.getConnectionId(), event.getRequesterId(), event.getRecipientId());
  processedEventRepository.save(new ProcessedEvent(event.getEventId(), Instant.now()));
}
```

## JWT / identity — never validate the token, just read headers

```java
// Pattern: API Gateway handles auth; services are trust-boundary internal
// Reason: re-validating the token in every service is duplicated work and couples services to Google's JWKS endpoint
@GetMapping("/scan-sessions")
public ResponseEntity<ScanSessionResponse> startScan(
    @RequestHeader("X-User-Id") UUID userId,
    @RequestHeader("X-User-Gender") String gender,
    @Valid @RequestBody StartScanRequest request) {
  // userId is already validated and injected by the gateway
}
```

## GlobalExceptionHandler (from shared-java-lib)

```java
// Pattern: Single Responsibility — one class handles all error translation
// Reason: each controller having its own error handling = duplication + inconsistency
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    return ResponseEntity.badRequest().body(
      new ErrorResponse("VALIDATION_ERROR", ex.getBindingResult().getFieldError().getDefaultMessage(), getTraceId())
    );
  }
}
```

## application.yml — always environment variables, no hardcoded values

```yaml
spring:
  datasource:
    url: ${DB_URL}                          # jdbc:sqlserver://sql-server:1433;databaseName=connections_db
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS}     # kafka:9092 in Docker, localhost:9092 locally
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
```

## Testing conventions

```java
// Unit test: Mockito for external deps, no Spring context = fast
@ExtendWith(MockitoExtension.class)
class TrustScoreCalculatorTest {
  // Pattern: pure function testing — TrustScoreCalculator has no DI so Mockito isn't even needed
  private final TrustScoreCalculator calculator = new TrustScoreCalculator();

  @Test void score_should_be_weighted_average_of_last_50_ratings() { ... }
}

// Integration test: Testcontainers for SQL Server + Kafka
@SpringBootTest
@Testcontainers
class ConnectionRepositoryIT {
  @Container
  static MSSQLServerContainer<?> sqlServer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest");
}
```
