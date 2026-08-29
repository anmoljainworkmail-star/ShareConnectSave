# Multi-Stage Build (T014): the `build` stage carries the full SDK (compilers,
# NuGet cache, ~800MB+) needed only to PRODUCE a compiled output. The `runtime`
# stage below starts fresh from a much smaller ASP.NET runtime image and copies
# in only the published binaries. Nothing needed to compile the app ever ships
# in the image that runs it — this is the infrastructure-level analogue of
# Single Responsibility: the SDK image's job is "produce artifacts," the
# runtime image's job is "execute artifacts." Collapsing them into one stage
# would still work, but every deploy would ship a compiler nobody runs in prod.
FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build
WORKDIR /src

# Docker layer caching: copy only the .csproj first and restore against it.
# As long as the .csproj doesn't change, Docker reuses this cached restore
# layer on rebuilds instead of re-downloading every NuGet package just because
# a .cs file changed below.
COPY api-gateway.csproj ./
RUN dotnet restore

COPY . .
RUN dotnet publish -c Release -o /app/publish --no-restore

# Runtime stage — slim ASP.NET runtime only, no SDK/compiler ever ships.
# Separation of Build-Time and Run-Time Concerns: this stage never runs
# `dotnet build`/`dotnet restore` — if it ever needs to, the multi-stage split
# has failed.
FROM mcr.microsoft.com/dotnet/aspnet:9.0 AS runtime
WORKDIR /app

# curl is required INSIDE the container because docker-compose.yml's existing
# healthcheck runs `curl -f http://localhost:8080/health` from inside this
# image, not from the host. mcr.microsoft.com/dotnet/aspnet:9.0 is
# Debian-based but does not ship curl by default. Clearing the apt list
# afterward keeps this layer's added size close to just curl's own footprint,
# which matters for the 200MB image budget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Only the published output crosses the stage boundary — never `COPY . .`
# here, which would drag the full source tree (.git, bin/, obj/) into the
# image that's actually meant to run in production.
COPY --from=build /app/publish .

EXPOSE 8080

# ASPNETCORE_URLS/ASPNETCORE_ENVIRONMENT are intentionally NOT set here via
# ENV — docker-compose.yml already sets both on the api-gateway service.
# Setting them again here would create two sources of truth that can drift.
ENTRYPOINT ["dotnet", "api-gateway.dll"]
