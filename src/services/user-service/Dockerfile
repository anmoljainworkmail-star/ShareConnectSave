# Multi-Stage Build — same template T014 established for api-gateway (see that
# Dockerfile's own comment for the full reasoning): a `build` stage carries the
# full SDK (compilers, NuGet cache, ~800MB+) needed only to PRODUCE a compiled
# output; the `runtime` stage below starts fresh from a much smaller ASP.NET
# runtime image and copies in only the published binaries. This file was
# missing entirely until full-stack integration testing tried to actually
# build user-service through Docker Compose — every prior QA pass ran it via
# `dotnet run` instead, so `docker-compose.yml`'s `build: dockerfile:
# Dockerfile` reference for this service had nothing to point at until now.
FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build
WORKDIR /src

# Docker layer caching: copy only the .csproj first and restore against it.
# As long as the .csproj doesn't change, Docker reuses this cached restore
# layer on rebuilds instead of re-downloading every NuGet package just because
# a .cs file changed below.
COPY user-service.csproj ./
RUN dotnet restore

COPY . .
RUN dotnet publish -c Release -o /app/publish --no-restore

# Runtime stage — slim ASP.NET runtime only, no SDK/compiler ever ships.
FROM mcr.microsoft.com/dotnet/aspnet:9.0 AS runtime
WORKDIR /app

# curl is required INSIDE the container because docker-compose.yml's
# healthcheck runs `curl -f http://localhost:8080/health` from inside this
# image, not from the host — same reasoning as api-gateway's identical layer.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Only the published output crosses the stage boundary — never `COPY . .`
# here, which would drag the full source tree (.git, bin/, obj/, the
# EF Core Migrations source) into the image that's actually meant to run.
COPY --from=build /app/publish .

EXPOSE 8080

# ASPNETCORE_URLS/ASPNETCORE_ENVIRONMENT are intentionally NOT set here via
# ENV — docker-compose.yml already sets both on the user-service service.
# Setting them again here would create two sources of truth that can drift.
ENTRYPOINT ["dotnet", "user-service.dll"]
