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

## OpenAPI / Contracts

- **`additionalProperties: false`** — a JSON Schema / OpenAPI keyword that rejects any object containing a field not explicitly listed under `properties`, so a future accidental (or convenient-in-the-moment) field addition fails contract validation instead of silently widening the shape. _(T003, `error-envelope.yaml`)_

---

_Add new entries only for concepts that actually appear in committed code — not everything mentioned in a skill file. If a concept reappears in a later task using the same mechanism, don't duplicate the entry._
