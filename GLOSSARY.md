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
- **`<PackageReference Include="..." Version="..." />`** (`.csproj`) — declares a NuGet dependency directly in the project file's XML; equivalent to running `dotnet add package`, but hand-editing avoids invoking the CLI tool for what's otherwise a one-line addition. _(T011, `api-gateway.csproj`)_

## JWT Validation & JWKS (.NET)

- **`MapInboundClaims = false`** (`JwtSecurityTokenHandler`) — disables the handler's default behavior of silently renaming short claim names (`sub`, `role`) to legacy long-form `ClaimTypes` URIs on the resulting principal; without it, code reading claims by their original short names gets back `null` even from a perfectly valid token. _(T012, `JwtValidationMiddleware.cs`)_
- **`RequireHttps = false`** (`HttpDocumentRetriever`, `Microsoft.IdentityModel.Protocols`) — allows fetching a JWKS/OIDC document over plain HTTP instead of refusing outright; the library defaults to `true` since JWKS fetches normally cross the public internet, but an internal Docker Compose network has no TLS between services. _(T012, `JwksService.cs`)_
- **`ValidAlgorithms`** (`TokenValidationParameters`) — explicitly restricts which signing algorithm(s) a token's signature is allowed to be checked against, instead of relying on implicit key-type matching; pins out a class of "algorithm confusion" attacks. _(T012, `JwtValidationMiddleware.cs`)_
- **`ConfigurationManager<T>`** (`Microsoft.IdentityModel.Protocols`) — a generic fetch-once-cache-and-auto-refresh helper: given a retriever and a refresh interval, `GetConfigurationAsync()` returns the cached value until the interval elapses, then transparently re-fetches on the next call. _(T012, `JwksService.cs`)_
- **`IConfigurationRetriever<T>`** — the plug-in interface `ConfigurationManager<T>` calls to actually fetch and parse a document; implementing it directly (rather than subclassing `ConfigurationManager<T>`) is what let this project supply a bare-JWKS parser where the library only ships one for full OIDC discovery documents. _(T012, `JwksService.cs`)_
- **`AddHttpClient<TInterface, TImplementation>()`** (typed client) — registers a class through `IHttpClientFactory`, automatically injecting a pooled, periodically-recycled `HttpClient` into its constructor; avoids both the socket-exhaustion risk of `new HttpClient()` per call and the stale-DNS risk of a hand-rolled long-lived singleton holding one connection pool forever. _(T012, `Program.cs`)_

## Rate Limiting (.NET)

- **`PartitionedRateLimiter<TResource, TPartitionKey>.Create(...)`** (`System.Threading.RateLimiting`) — builds a limiter whose actual limit-checking logic is deferred to a per-request partition-key function, instead of one fixed limit shared by every caller regardless of who they are. _(T013, `Program.cs`)_
- **`RateLimitPartition.Get(partitionKey, factory)`** — tells the framework "reuse this exact limiter instance for this partition key"; the factory delegate only runs the first time a given key is seen, not on every request against that key. _(T013, `RateLimitPartitionRegistry.cs`)_
- **`HttpContext.RequestServices`** — the DI scope tied to the current request. Resolving a singleton from it returns the same instance the app's real root container already created — unlike a separately constructed `ServiceProvider`, which would build its own, disconnected copy of every "singleton." _(T013, `Program.cs`)_
- **`Response.OnStarting(callback)`** — registers a callback that fires exactly once, the instant before the response's status code and headers are actually sent — late enough to know the final outcome (success, or a middleware's own rejection), but still early enough to add a header to it. _(T013, `RateLimitHeadersMiddleware.cs`)_
- **`MemoryCacheEntryOptions.RegisterPostEvictionCallback`** (`IMemoryCache`) — attaches a callback that fires the moment an entry is evicted (by expiry or manual removal); used here to dispose the evicted rate limiter's internal timer instead of leaking it forever. _(T013, `RateLimitPartitionRegistry.cs`)_

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
- **`entrypoint: ["/bin/bash", "/script.sh"]`** — replaces the image's built-in entrypoint entirely, not just the arguments passed to it. Different from `command` above: the `bitnami/kafka` image's default entrypoint starts a broker process, so a one-shot init job has to override the entrypoint itself to run a plain script instead. _(T009, `docker-compose.yml`)_

## Kafka (Bitnami image) / Bash

- **`KAFKA_CFG_*` environment variables** — the Bitnami Kafka image's convention for setting `server.properties` at container start: strip the property name's dots, uppercase it, prefix `KAFKA_CFG_` (e.g. `advertised.listeners` → `KAFKA_CFG_ADVERTISED_LISTENERS`). Lets broker config live in Compose YAML instead of a mounted properties file. _(T009, `docker-compose.yml`)_
- **`kafka-topics.sh --create --if-not-exists`** — the `--if-not-exists` flag makes topic creation a no-op instead of an error when the topic is already there, so the script can be re-run safely on every `docker compose up`. _(T009, `kafka-init.sh`)_
- **`set -euo pipefail`** — exits immediately on any command failure (`-e`), treats an unset variable as an error (`-u`), and makes a pipeline fail if any stage of it fails, not just the last one (`-o pipefail`). Without it, a failed step could be silently swallowed and the script would still exit 0. _(T009, `kafka-init.sh`)_

## Git / .gitattributes

- **`*.sh text eol=lf`** — forces any `.sh` file to always be stored and checked out with LF line endings, overriding a repo's `core.autocrlf` setting for that file type. Needed here because a CRLF-terminated shebang line breaks bash when the script is bind-mounted from a Windows working tree into a Linux container. _(T009, `.gitattributes`)_

## Redis / redis.conf

- **`maxmemory <size>`** — a hard cap on how much memory Redis's dataset may use; once reached, what happens next is governed entirely by `maxmemory-policy`. _(T008, `redis.conf`)_
- **`maxmemory-policy allkeys-lru`** — once `maxmemory` is hit, silently evict the least-recently-used key across the whole keyspace to make room for a new write. Redis's actual factory default, `noeviction`, does the opposite — it rejects the new write with an OOM error instead. _(T008, `redis.conf`)_
- **`save ""`** — disables RDB snapshotting entirely, so the dataset does not survive a container restart. Combined with `appendonly` left at its default (`no`), no persistence mechanism is active at all. _(T008, `redis.conf`)_
- **Logical database index (`databases 16`, selected via `SELECT n` or a client's connection options)** — a single Redis server process can host up to 16 independent numbered keyspaces; picking different indices for unrelated data lets them share one server without their keys ever colliding, with no enforcement beyond every client agreeing to use the right number. _(T008, `redis.conf`)_

## Dependency Injection & EF Core Wiring (.NET)

- **`AddDbContext<TContext>(options => ...)`** — registers a `DbContext` type in the DI container with a Scoped lifetime by default (one instance built per HTTP request, then discarded); the `options` delegate picks the database provider (`UseSqlServer`) and how to connect. _(T015, `Program.cs`)_
- **`builder.Configuration.GetConnectionString("Name")`** — shorthand for reading configuration key `ConnectionStrings:Name`; ASP.NET Core's environment-variable configuration provider populates that key from an env var literally named `ConnectionStrings__Name` (double underscore is the provider's documented separator for nested keys). _(T015, `Program.cs`)_
- **`AddScoped<TInterface, TImplementation>()`** — registers a DI mapping with per-request lifetime: a fresh instance is built the first time a request asks for `TInterface`, and every later resolution within that same request gets the same instance back. Contrast with `AddSingleton` (one instance for the app's entire lifetime) and `AddTransient` (a brand-new instance on every single resolution, even twice in one request). _(T015, `Program.cs`)_

## YARP / ASP.NET Core Reverse Proxy

- **`AddReverseProxy().LoadFromConfig(builder.Configuration.GetSection("ReverseProxy"))`** — registers YARP's proxy services and builds its entire route/cluster table once at startup from a config section, instead of routes being defined in C# code. _(T011, `Program.cs`)_
- **`app.MapReverseProxy()`** — registers YARP's proxy handler as the endpoint for every route loaded above; a request matching none of them falls through to ASP.NET Core's default 404 since no catch-all/fallback is mapped. _(T011, `Program.cs`)_
- **`"Transforms": [{ "PathRemovePrefix": "/user" }]`** — a YARP route transform that strips the matched prefix before forwarding, so `/user/api/profile` reaches the downstream service as `/api/profile` instead of the prefix being forwarded too. _(T011, `appsettings.json`)_

## PowerShell

- **`[Parameter(Mandatory)]` / `[ValidateSet('A','B')]`** — function parameter attributes: `Mandatory` makes PowerShell prompt/fail if the caller omits the argument instead of silently passing `$null`; `ValidateSet` rejects any value not in the listed set before the function body ever runs. _(T010, `dev-setup.ps1`)_
- **`$script:` scope modifier** — inside a function, `$summary += $x` only mutates a local copy of `$summary`, because PowerShell reads outer-scope variables but writes create a new local one; explicitly assigning back to `$script:summary` is what makes the change visible to the rest of the script. _(T010, `dev-setup.ps1`)_
- **`2>&1`** — merges a command's stderr stream into stdout so both can be captured together; needed here because `java -version` writes its version banner to stderr by long-standing JVM convention, not stdout. _(T010, `dev-setup.ps1`)_
- **`-ErrorAction SilentlyContinue`** — turns a cmdlet's non-terminating error into a silent no-op (still returns `$null`/empty) instead of printing red error text, used for existence checks where "not found" is an expected, normal outcome rather than a failure. _(T010, `dev-setup.ps1`)_

## Docker / Dockerfile (multi-stage builds)

- **`dotnet publish` vs `dotnet build`** — `build` compiles for the inner dev loop and assumes the rest of the project/solution stays nearby (output isn't self-contained). `publish` assembles a self-sufficient deployable folder: every referenced DLL actually copied in (not just path-referenced), plus `*.deps.json` (dependency manifest) and `*.runtimeconfig.json` (which .NET version/settings to launch with) — and defaults to `Release`, not `Debug`. The runtime stage of a multi-stage Dockerfile has no `.csproj`, no solution structure, nothing else nearby — only `publish`'s output is guaranteed to run in that kind of isolation, so it's what gets copied across the stage boundary, never `build`'s output. _(T014, `Dockerfile`)_
- **`FROM <image> AS <stage-name>`** — names a build stage so a later stage can reference it by name (e.g. `COPY --from=build`) instead of by numeric index; a Dockerfile can define multiple `FROM` stages, only the last one becomes the final tagged image. _(T014, `Dockerfile`)_
- **`COPY --from=build /app/publish .`** — copies files from a *previous build stage's filesystem*, not from the host machine; this is the one line that crosses the boundary between the SDK stage and the runtime stage, and it's the only thing from the `build` stage that survives into the final image. _(T014, `Dockerfile`)_

---

_Add new entries only for concepts that actually appear in committed code — not everything mentioned in a skill file. If a concept reappears in a later task using the same mechanism, don't duplicate the entry._
