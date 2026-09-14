# T014 — Gateway Docker Image

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see the ticket file or `README.md`'s concepts
section. Generated/updated via `/diagram-task T0XX`.

```mermaid
flowchart TD
    A[docker compose build api-gateway] --> A2[Compose reads docker-compose.yml:<br/>build.context + dockerfile: Dockerfile]
    A2 --> A3[Docker build engine<br/>executes Dockerfile]
    A3 --> SG1

    subgraph SG1[Temp build container - discarded after build]
        B[FROM sdk:9.0<br/>has compiler+SDK+NuGet cache]
        B --> C[COPY csproj, dotnet restore<br/>cached if csproj unchanged]
        C --> D[COPY . ., dotnet publish<br/>DLLs written to /app/publish]
    end

    D --> SG2

    subgraph SG2[Runtime image build]
        E[FROM aspnet:9.0<br/>runtime only, no compiler]
        E --> F[apt-get install curl]
        F --> G[COPY --from=build<br/>only /app/publish DLLs]
    end

    G --> H[Image tagged<br/>shareconnectsave/api-gateway:dev]
    H --> I[docker compose up -d api-gateway]

    subgraph SG3[api-gateway container - actually running]
        I --> J[Container starts:<br/>dotnet api-gateway.dll]
        J --> K[Healthcheck timer:<br/>40s start_period, every 30s]
        K --> L[curl -f localhost:8080/health<br/>inside container, no auth header]
        L --> M{JwtValidationMiddleware:<br/>GET + path = /health?}
        M -- yes --> N[Bypass, skip straight to next]
        M -- no --> O[normal JWT flow - see T012 workflow]
        N --> P[MapGet /health -> 200 OK]
    end

    P --> Q{curl exit code}
    Q -- 0 --> R[Container: healthy]
    Q -- nonzero --> S[Container: unhealthy,<br/>dependents never start]
```

SG1's container is compile-only and thrown away — nothing in it reaches the tagged image except the DLLs copied out at G. SG3 is the only container that's actually "the api-gateway" a developer thinks of as running; it's spun up fresh from the image, minutes or builds after SG1 ever existed. Before the `/health` bypass, branch M hit "no", got 401'd by its own gateway, and the container sat unhealthy forever.
