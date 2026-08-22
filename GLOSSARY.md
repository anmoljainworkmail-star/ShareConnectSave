# Code Concepts Glossary

One-liner reference for every annotation, attribute, or language keyword introduced in the actual code, in the order it first appeared. This is syntax-level — "what does this specific thing do and why is it here" — not architecture. For higher-level patterns (Saga, Outbox, Event-Driven Architecture, etc.), see README's **"Concepts I can explain cold because of this"** section instead.

Updated after every `/push` — new syntax introduced by that task gets one line here, added once, never repeated.

---

## Java / Spring Boot

- **`@RestControllerAdvice`** — marks a class as a global exception handler for every `@RestController` in the app, so one class catches errors application-wide instead of each controller writing its own try/catch. _(T003, `GlobalExceptionHandler.java`)_
- **`@ExceptionHandler(SomeException.class)`** — marks a method inside a `@RestControllerAdvice` as the handler for one specific exception type; Spring routes a thrown exception to whichever handler method matches its type most specifically. _(T003, `GlobalExceptionHandler.java`)_
- **Java `record`** — a data-only class where you declare just the field list; the compiler generates the constructor, getters, `equals`/`hashCode`/`toString` for you. Used when a type is pure data with no behavior. _(T003, `ErrorResponse.java`)_
- **`MDC` (`org.slf4j.MDC`)** — a thread-local key-value map that a request's logging/tracing setup populates per-request; reading `MDC.get("traceId")` retrieves the current request's trace ID without passing it through every method signature by hand. _(T003, `GlobalExceptionHandler.java`)_
- **Maven `<scope>provided</scope>`** — "compile against this dependency, but don't bundle it into the jar or pull it transitively into consumers" — the consumer is trusted to supply the real implementation at runtime. Used so `shared-java-lib` depends on the *logging abstraction* (`slf4j-api`) without dictating which concrete logger every service must use. _(T003, `shared-java-lib/pom.xml`)_

## C# / .NET

- **C# `record`** — same idea as a Java record: an immutable data type where two instances are equal if their values match, not just if they're the same object reference. _(T003, `ErrorResponse.cs`)_
- **`[JsonPropertyName("...")]`** (`System.Text.Json`) — pins the exact JSON key name for a property or record parameter, overriding whatever the ambient serializer's naming policy (e.g. PascalCase by default) would otherwise produce. Needed because C#'s default casing doesn't match Java's. _(T003, `ErrorResponse.cs`)_
- **`IDesignTimeDbContextFactory<TContext>`** — a factory interface EF Core's CLI tooling (`dotnet ef migrations add`) looks for when it needs to construct a `DbContext` outside the app's normal startup (no host, no DI container running); implementing it is what lets `dotnet ef` build a context to diff against, using its own connection string lookup rather than the app's runtime configuration path. _(T006, `AppDbContextFactory.cs`)_
- **`entity.HasIndex(x => x.Prop).IsUnique()`** (EF Core fluent API, in `OnModelCreating`) — configures a database-level unique index on a column via code instead of a data annotation; EF Core generates the corresponding migration DDL from this call. _(T006, `AppDbContext.cs`)_

## SQL Server / T-SQL

- **`V{version}__{description}.sql`** (Flyway naming convention) — two underscores between version and description; Flyway checksums the file after its first run and refuses to start if the bytes ever change, so a migration can't silently re-run or drift. _(T006, `V001__discovery_service_initial_schema.sql`)_
- **`AS geography::Point(lat, lng, srid) PERSISTED`** — a computed column: its value is derived from other columns in the same row (here, turning two floats into a native spatial point) and physically stored (`PERSISTED`) rather than recalculated on every read. _(T006, `V001__discovery_service_initial_schema.sql`)_
- **`CREATE SPATIAL INDEX ... USING GEOGRAPHY_AUTO_GRID`** — indexes a `geography` column by tessellating the earth into a grid so a proximity/distance query can prune almost the entire table before doing real distance math, instead of scanning every row. _(T006, `V001__discovery_service_initial_schema.sql`)_
- **`SET ANSI_NULLS/ANSI_PADDING/ANSI_WARNINGS/ARITHABORT/CONCAT_NULL_YIELDS_NULL/NUMERIC_ROUNDABORT/QUOTED_IDENTIFIER`** — session-level options SQL Server requires to all be set to specific values before it will let you index a computed column; different client drivers (e.g. `sqlcmd` vs. JDBC) default these differently, so relying on a default instead of setting them explicitly can make a migration pass under manual testing and fail in the real app. _(T006, `V001__discovery_service_initial_schema.sql`)_
- **`CREATE UNIQUE INDEX ... WHERE <condition>`** (filtered unique index) — enforces uniqueness only among rows matching the `WHERE` clause, so e.g. only one currently-`PENDING` row per key pair is disallowed, while old `EXPIRED`/`ACCEPTED` history doesn't block a legitimate future row. _(T006, `V001__connection_service_initial_schema.sql`)_
- **`CHECK (col IS NULL OR ISJSON(col)=1)`** — a check constraint that validates a `NVARCHAR` column contains syntactically valid JSON (when present), without needing a separate normalized table for the JSON's contents. _(T006, `V001__rating_service_initial_schema.sql`)_

## OpenAPI / Contracts

- **`additionalProperties: false`** — a JSON Schema / OpenAPI keyword that rejects any object containing a field not explicitly listed under `properties`, so a future accidental (or convenient-in-the-moment) field addition fails contract validation instead of silently widening the shape. _(T003, `error-envelope.yaml`)_
- **`openapi: 3.1.0`** — declares the spec version; 3.1 (unlike 3.0.x) is fully JSON Schema-compatible, so the same `type`/`enum`/`$ref` rules work identically whether the schema is embedded in an OpenAPI file or a standalone JSON Schema document like `error-envelope.yaml`. _(T005, `user-service.yaml`)_
- **`$ref: './error-envelope.yaml#/components/schemas/ErrorResponse'`** — a JSON Reference: the part before `#` is a relative path to another file, the part after is a JSON Pointer into that file's structure. This is what lets eight separate service specs all point at one shared error schema instead of each redefining it. _(T005, `user-service.yaml`)_
- **`components.responses` (named, reusable response objects)** — defines a response shape once per file (e.g. `BadRequest`, `Unauthorized`) and has every operation that can return it `$ref` the same block, instead of repeating the same `400`/`401` schema under every single endpoint. _(T005, `user-service.yaml`)_

## MongoDB / mongosh

- **`db.getSiblingDB('name')`** — switches context to a different database within the same `mongosh` connection without opening a separate connection string; used so one init script can provision multiple logical databases in a single run. _(T007, `01-init-chat.js`)_
- **`createIndex({field: 1}, {expireAfterSeconds: N})`** — creates a TTL index: MongoDB's own background sweep deletes any document once `field`'s timestamp is more than `N` seconds in the past, no application code involved. _(T007, `01-init-chat.js`)_
- **`/docker-entrypoint-initdb.d/`** — a directory the official `mongo` image scans on container startup, running every `.js`/`.sh` file inside via `mongosh` — but only the very first time the container starts against an empty data volume, never on later restarts. _(T007, `docker-compose.yml`)_
- **`process.env.VAR_NAME` inside a mongosh script** — `mongosh` is itself a Node.js process, so it inherits whatever environment variables Docker Compose passes into the container; this is how an init script reads config like `MONGO_TTL_SECONDS` with no extra plumbing. _(T007, `01-init-chat.js`)_

## Docker Compose / YAML

- **`${VAR:?message}`** — reads env var `VAR`; if it's unset or empty, Compose refuses to start the container and prints `message` instead of silently proceeding with a blank value. A fail-fast guard clause for required config. _(T004, `docker-compose.yml`)_
- **`${VAR:-default}`** — reads env var `VAR`; if it's unset or empty, falls back to `default` instead of failing. Used for config that has a sane dev default (e.g. a hostname), as opposed to `:?` which is reserved for things that must never silently default (secrets). _(T004, `docker-compose.override.yml`)_
- **`depends_on: <service>: condition: service_healthy`** — waits for the target service's `healthcheck` to report healthy, not just for its container process to exist, before starting this service. _(T004, `docker-compose.yml`)_
- **`depends_on: <service>: condition: service_completed_successfully`** — waits for a one-shot service (`restart: "no"`) to exit with code 0, rather than waiting on a healthcheck (one-shot jobs don't stay running long enough to have one). _(T004, `docker-compose.yml`)_
- **`command: ["redis-server", "/path/to/redis.conf"]`** — overrides the image's default startup arguments. Mounting a custom config file into a container via a volume does not make the stock image load it on its own; the container still has to be told, via `command`, to start the process with that file as an argument. _(T008, `docker-compose.yml`)_

## Redis / redis.conf

- **`maxmemory <size>`** — a hard cap on how much memory Redis's dataset may use; once reached, what happens next is governed entirely by `maxmemory-policy`. _(T008, `redis.conf`)_
- **`maxmemory-policy allkeys-lru`** — once `maxmemory` is hit, silently evict the least-recently-used key across the whole keyspace to make room for a new write. Redis's actual factory default, `noeviction`, does the opposite — it rejects the new write with an OOM error instead. _(T008, `redis.conf`)_
- **`save ""`** — disables RDB snapshotting entirely, so the dataset does not survive a container restart. Combined with `appendonly` left at its default (`no`), no persistence mechanism is active at all. _(T008, `redis.conf`)_
- **Logical database index (`databases 16`, selected via `SELECT n` or a client's connection options)** — a single Redis server process can host up to 16 independent numbered keyspaces; picking different indices for unrelated data lets them share one server without their keys ever colliding, with no enforcement beyond every client agreeing to use the right number. _(T008, `redis.conf`)_

---

_Add new entries only for concepts that actually appear in committed code — not everything mentioned in a skill file. If a concept reappears in a later task using the same mechanism, don't duplicate the entry._
