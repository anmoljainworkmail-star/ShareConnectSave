# syntax=docker/dockerfile:1
# The line above pins the BuildKit Dockerfile frontend so `RUN --mount=type=cache`
# below is available regardless of the local Docker Engine's default frontend
# version — see the Maven cache-mount fix a few lines down for why it's needed.
#
# Multi-Stage Build — same shape discovery-service/Dockerfile (T028) and
# connection-service/Dockerfile (T034) already established for this project's
# Java services: a `build` stage with the full JDK plus Maven's dependency
# cache produces a runnable jar; a `runtime` stage starts fresh from a
# minimal JRE-only Alpine image and copies in only that jar. Nothing needed
# to compile the app (javac, Maven itself, the ~/.m2 dependency cache, test
# classes) ever ships in the image that runs it — "build once, ship lean."
#
# NOTE: the build context for this Dockerfile is `src/services` (the parent
# of this directory — see docker-compose.yml, fixed by this ticket to match
# discovery-service/connection-service's own context), not `rating-service/`
# alone — see the shared-java-lib step below for why.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Local multi-module dependency, not a Maven Central artifact: shared-java-lib
# (ErrorResponse + GlobalExceptionHandler — rating-service's own
# ExceptionHandlingConfig and RatingExceptionHandler both depend on it) has no
# reactor/parent pom tying it to rating-service, so this build stage — which
# starts with an EMPTY ~/.m2 — has to `mvn install` it from source before
# rating-service's own build can resolve the dependency. Installing it as its
# own layer (before rating-service's pom.xml is even copied in) means this
# step is cached and skipped on rebuilds unless shared-java-lib's own source
# changes.
COPY shared-java-lib/.mvn/ shared-java-lib/.mvn/
COPY shared-java-lib/mvnw shared-java-lib/pom.xml shared-java-lib/
RUN chmod +x shared-java-lib/mvnw
COPY shared-java-lib/src shared-java-lib/src
# Cache mount persists ~/.m2 across builds (BuildKit-managed, not a bind mount
# into the image — nothing here ships in the final jar/runtime stage), so
# only genuinely new dependency versions ever hit the network again.
RUN --mount=type=cache,target=/root/.m2 cd shared-java-lib && ./mvnw install -DskipTests -B

# Maven Wrapper, not a globally-installed Maven — pins the exact Maven
# version (see .mvn/wrapper/maven-wrapper.properties) so a build here
# reproduces the same build anywhere else.
COPY rating-service/.mvn/ rating-service/.mvn/
COPY rating-service/mvnw rating-service/pom.xml rating-service/
RUN chmod +x rating-service/mvnw

# Docker layer caching: resolve dependencies against pom.xml alone before the
# source tree is copied in, so a source-only change doesn't re-fetch every
# Maven artifact. shared-java-lib is already sitting in this stage's local
# .m2 (installed above), so this resolves cleanly.
RUN --mount=type=cache,target=/root/.m2 cd rating-service && ./mvnw dependency:go-offline -B

COPY rating-service/src rating-service/src
# -DskipTests: this Dockerfile packages what T-series test tickets already
# verified in CI/local runs — a Docker build is not the place to re-run the
# suite. package (not just compile) is what produces the runnable fat jar
# under target/, via the spring-boot-maven-plugin repackage goal.
RUN --mount=type=cache,target=/root/.m2 cd rating-service && ./mvnw package -DskipTests -B

# Runtime stage — JRE-only Alpine, no JDK/compiler/Maven cache ever ships.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# curl is required INSIDE the container because docker-compose.yml's
# healthcheck runs `curl -f http://localhost:8080/actuator/health` from
# inside this image, not from the host.
RUN apk add --no-cache curl

# Only the packaged jar crosses the stage boundary — never COPY the source
# tree or ~/.m2 cache here. The wildcard tolerates the exact jar filename
# (artifactId + version from pom.xml) without hardcoding a version string
# that would go stale the moment pom.xml's <version> changes.
COPY --from=build /src/rating-service/target/*.jar app.jar

EXPOSE 8080

# SPRING_PROFILES_ACTIVE / server port are intentionally NOT set here via
# ENV — docker-compose.yml already sets SPRING_PROFILES_ACTIVE=docker on the
# rating-service service, and Spring Boot's default server.port (8080) is
# left alone. Setting either again here would create two sources of truth
# that can drift.
ENTRYPOINT ["java", "-jar", "app.jar"]
