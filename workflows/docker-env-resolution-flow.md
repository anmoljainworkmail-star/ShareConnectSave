# Docker Env & Secrets — Build vs Runtime, Dev vs Prod

Quick-glance boxes-and-arrows reference for how a request/process actually flows through
the code. No prose — if you need the "why," see T004/T014/T020/T025/T028's ticket files or
`README.md`'s concepts section. Generated/updated via `/diagram-task`.

```mermaid
flowchart TD
    A["Dockerfile build stage<br/>mvnw package / dotnet publish"] --> B["Dockerfile runtime stage<br/>COPY binary only"]
    B --> C["Image<br/>compiled code, no env knowledge"]
    C --> D{"Where does it run?"}

    D -->|"dev: docker compose up"| E["Compose reads<br/>docker-compose.yml + override.yml"]
    E --> F["Resolve ${VAR}<br/>against host .env"]
    F --> G["Inject resolved values<br/>as container env vars"]
    G --> H(["Container running — dev"])

    D -->|"prod: pulled by ECS/EKS"| I["Task def / pod spec<br/>environment + secrets refs"]
    I --> J["Orchestrator resolves refs<br/>Secrets Manager / Vault / K8s Secret"]
    J --> K["Inject resolved values<br/>as container env vars"]
    K --> L(["Container running — prod"])
```

Build stage (`docker compose build`) never reads `.env` or `environment:` — that block only matters at container start, and the image is identical whether it ends up on the dev or prod branch.
