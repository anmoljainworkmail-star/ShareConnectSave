Start the ShareConnectSave infrastructure stack.

## Steps

1. Check Docker is running:
   ```
   docker info
   ```
   If it fails, say "Docker Desktop is not running." and stop.

2. Start all containers from the project root:
   ```
   docker compose up -d
   ```

3. Wait up to 90 seconds for containers to become healthy. Poll every 5 seconds:
   ```
   docker compose ps
   ```

4. Once stable, show a summary table:
   ```
   docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
   ```

5. Print the local dev URLs:
   - API Gateway:  http://localhost:8080
   - Kafka UI:     http://localhost:9000
   - Jaeger:       http://localhost:16686
   - SQL Server:   localhost:1433
   - MongoDB:      localhost:27017
   - Redis:        localhost:6379

If any container is unhealthy after 90s, show its logs:
```
docker compose logs <container-name> --tail 50
```
