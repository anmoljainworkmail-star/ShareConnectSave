# Multi-Stage Build: one Dockerfile, two images layered on top of each other.
# Real-world analogy — a shipyard builds a boat inside a huge dry dock full of
# cranes, welders, and scaffolding, but only the finished boat sails out; the
# dry dock never leaves port. Here the "dry dock" is the full .NET SDK (compiler,
# NuGet cache, source tree) and the "boat" is just the published DLLs. Without
# this, the image we ship would drag the entire SDK and our source code into
# production — bigger attack surface, slower pulls, and source code sitting
# inside a container anyone with registry access could inspect.
FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build
WORKDIR /src

# Docker Layer Caching: copying only the .csproj before `dotnet restore` means
# this layer (and the slow NuGet download it triggers) is only invalidated when
# project *dependencies* change — not every time we edit a .cs file. If we did
# `COPY . .` first, Docker would see the source tree change on every edit and
# re-run restore from scratch each time, even when no package reference moved.
COPY chat-service.csproj ./
RUN dotnet restore

COPY . .
RUN dotnet publish -c Release -o /app/publish --no-restore

# Separation of Build-Time and Run-Time Concerns: the runtime stage never sees
# `dotnet build`/`dotnet restore` — it only ever runs what the build stage
# already compiled. This is the same idea as Chat Service's own Outbox Relay
# only publishing, never deciding business logic: each stage/class does the one
# job it owns, nothing more.
FROM mcr.microsoft.com/dotnet/aspnet:9.0 AS runtime
WORKDIR /app

# aspnet:9.0 is a slimmed runtime-only image and does not ship curl. The
# docker-compose.yml healthcheck for chat-service runs
# `curl -f http://localhost:8080/health` *inside* this container, so curl has
# to be installed here, not just available on the host.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Only the published output crosses the stage boundary — never `COPY . .` here,
# or we'd drag the SDK stage's source tree and intermediate build artifacts
# back into the "boat" we're trying to keep lean.
COPY --from=build /app/publish .

EXPOSE 8080

# ASPNETCORE_URLS / ASPNETCORE_ENVIRONMENT are intentionally NOT set via ENV
# here — docker-compose.yml and docker-compose.override.yml already own both.
# Setting them again here would create two sources of truth that can silently
# drift apart (No Hardcoded Config principle: environment-specific values live
# in environment configuration, not baked into the image).
ENTRYPOINT ["dotnet", "chat-service.dll"]
