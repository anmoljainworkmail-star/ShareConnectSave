# syntax=docker/dockerfile:1
# The line above pins the BuildKit Dockerfile frontend so `RUN --mount=type=cache`
# below is available regardless of the local Docker Engine's default frontend
# version — see the Maven cache-mount fix a few lines down for why it's needed.
#
# Multi-Stage Build (T028) — same shape T014 established for api-gateway/
# user-service (.NET), now applied to the first Java service to get a
# Dockerfile. The `build` stage carries the full JDK plus Maven's dependency
# cache (~600MB+ once Spring Boot, Kafka, Resilience4j, etc. are resolved) —
# needed only to COMPILE the app into a runnable jar. The `runtime` stage
# below starts fresh from a minimal JRE-only Alpine image and copies in only
# that compiled jar. Nothing needed to compile the app (javac, Maven itself,
# the ~/.m2 dependency cache, test classes) ever ships in the image that runs
# it — same "build once, ship lean" principle as every other service's
# Dockerfile, and the same infra-level Single Responsibility split: the JDK
# image's job is "produce a jar," the JRE image's job is "run a jar."
#
# NOTE: the build context for this Dockerfile is `src/services` (the parent
# of this directory, see docker-compose.yml), not `discovery-service/` alone —
# see the shared-java-lib step below for why.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Local multi-module dependency, not a Maven Central artifact: shared-java-lib
# (ErrorResponse + GlobalExceptionHandler, reused by all five Java services)
# has no reactor/parent pom tying it to discovery-service, so on a developer's
# machine it only resolves because it was `mvn install`-ed into ~/.m2 by hand
# at some point. This build stage starts with an EMPTY .m2, so that install
# has to happen again here, from source, before discovery-service's own build
# can resolve the dependency. Installing it as its own layer (before
# discovery-service's pom.xml is even copied in) means this expensive step is
# cached and skipped on rebuilds unless shared-java-lib's own source changes.
COPY shared-java-lib/.mvn/ shared-java-lib/.mvn/
COPY shared-java-lib/mvnw shared-java-lib/pom.xml shared-java-lib/
RUN chmod +x shared-java-lib/mvnw
COPY shared-java-lib/src shared-java-lib/src
# Bug fix (testing-bugs-pending.md #4): without a persisted .m2, editing ANY
# shared-java-lib source file invalidates this layer and forces Maven to
# re-download its entire dependency tree from a cold cache on every rebuild —
# a 3-line logging change shouldn't cost the same as the very first build ever
# run. The cache mount persists ~/.m2 across builds (BuildKit-managed, not a
# bind mount into the image — nothing here ships in the final jar/runtime
# stage), so only genuinely NEW dependency versions ever hit the network again.
RUN --mount=type=cache,target=/root/.m2 cd shared-java-lib && ./mvnw install -DskipTests -B

# Maven Wrapper, not a globally-installed Maven — mirrors the ticket's own
# rule for why .NET Dockerfiles never assume a host-installed SDK version.
# mvnw pins the exact Maven version (see .mvn/wrapper/maven-wrapper.properties)
# so a build here reproduces the same build anywhere else, instead of
# whatever `mvn` happens to resolve to on this machine's PATH.
COPY discovery-service/.mvn/ discovery-service/.mvn/
COPY discovery-service/mvnw discovery-service/pom.xml discovery-service/
RUN chmod +x discovery-service/mvnw

# Docker layer caching: resolve dependencies against pom.xml alone before the
# source tree is copied in. As long as pom.xml doesn't change, Docker reuses
# this cached dependency-download layer on rebuilds instead of re-fetching
# every Maven artifact just because a .java file changed below. go-offline
# only pulls dependencies (and plugins needed to do so) — it does not compile
# anything yet, so a source-only change still hits this cache. shared-java-lib
# is already sitting in this stage's local .m2 (installed above), so this
# resolves cleanly instead of failing with "artifact could not be resolved."
RUN --mount=type=cache,target=/root/.m2 cd discovery-service && ./mvnw dependency:go-offline -B

COPY discovery-service/src discovery-service/src
# -DskipTests: this Dockerfile packages what T-series test tickets already
# verified in CI/local runs — a Docker build is not the place to re-run the
# suite. package (not just compile) is what actually produces the runnable
# fat jar under target/, via the spring-boot-maven-plugin repackage goal
# already configured in pom.xml. Same cache mount as the two Maven steps
# above (testing-bugs-pending.md #4) — this step also touches ~/.m2 for
# plugin resolution, not just the dependency:go-offline step.
RUN --mount=type=cache,target=/root/.m2 cd discovery-service && ./mvnw package -DskipTests -B

# Runtime stage — JRE-only Alpine, no JDK/compiler/Maven cache ever ships.
# Alpine's musl-libc base plus a JRE-not-JDK image is what keeps this stage
# well under the ticket's 300MB budget; the build stage above (eclipse-
# temurin:21-jdk, a full Debian-based JDK) is discarded entirely once its
# jar is copied out — Docker never ships intermediate build-stage layers.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# curl is required INSIDE the container because docker-compose.yml's
# healthcheck runs `curl -f http://localhost:8080/actuator/health` from
# inside this image, not from the host — same reasoning as every .NET
# service's Dockerfile in this repo, adapted for Alpine's package manager
# (apk, not apt-get). --no-cache skips writing the package index to disk
# at all, which on Alpine is the equivalent of apt's "rm -rf apt lists"
# cleanup step — one line instead of two.
RUN apk add --no-cache curl

# Only the packaged jar crosses the stage boundary — never COPY the source
# tree or ~/.m2 cache here, which would drag the entire build toolchain's
# footprint into the image that's actually meant to run. The wildcard
# tolerates the exact jar filename (artifactId + version from pom.xml)
# without this Dockerfile needing to hardcode a version string that would
# go stale the moment pom.xml's <version> changes.
COPY --from=build /src/discovery-service/target/*.jar app.jar

EXPOSE 8080

# SPRING_PROFILES_ACTIVE / server port are intentionally NOT set here via
# ENV — docker-compose.yml already sets SPRING_PROFILES_ACTIVE=docker on the
# discovery-service service, and Spring Boot's default server.port (8080)
# is left alone. Setting either again here would create two sources of
# truth that can drift, same rule the .NET Dockerfiles already follow for
# ASPNETCORE_URLS/ASPNETCORE_ENVIRONMENT.
ENTRYPOINT ["java", "-jar", "app.jar"]
