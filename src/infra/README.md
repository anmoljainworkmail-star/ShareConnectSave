# Infra — local dev stack

This folder holds the Docker Compose topology for ShareConnectSave's local
development environment (SQL Server, MongoDB, Kafka, Redis, Kafka UI, and the
application service stubs). See `docker-compose.yml` for the full topology and
`.env.example` for the environment variables this compose file reads.

## Redis (T008)

One Redis 7 container serves two unrelated purposes for the platform:

1. **Discovery Service cache** — cache-aside storage for nearby-scan results,
   so repeated scans don't re-hit SQL Server / re-run geospatial queries on
   every request. Target: keep scan latency under the P95 < 500ms budget.
2. **SignalR backplane** — shared connection/group state for Chat and
   Notification service instances, so a message sent by one instance reaches
   a WebSocket client connected to a *different* instance. Without this,
   horizontally scaling either service breaks realtime delivery.

Running these on **one Redis instance** (instead of two separate containers)
keeps the dev stack lighter to run and reason about. What keeps them from
colliding is **logical database separation** — Redis's built-in support for
up to 16 independent numbered databases inside a single server process.
Think of it as one filing cabinet with two labeled drawers, rather than two
separate cabinets:

| DB index | Owner | Purpose |
|---|---|---|
| `0` | Discovery Service | Nearby-scan result cache (cache-aside pattern) |
| `1` | Chat + Notification Services | SignalR backplane (shared WebSocket/group state) |

**This table is the contract.** There is no Redis config directive that
enforces it — each service's Redis client must connect with the matching
database index (e.g. `SELECT 1` for the SignalR backplane, or the
equivalent connection-string/options field in whatever client library it
uses). Read this table before wiring up a new consumer of this Redis
instance; do not guess an index.

### Config

`redis.conf` (mounted read-only into the container at
`/usr/local/etc/redis/redis.conf`) sets:

- `maxmemory 256mb` + `maxmemory-policy allkeys-lru` — bounded memory with
  least-recently-used eviction, so the dev cache can't OOM the host machine.
- `save ""` — persistence disabled. Both use cases above hold only derived /
  transient data (the cache can be rebuilt from SQL Server; backplane state
  is meaningless once the SignalR connections it describes close), so there
  is nothing worth surviving a container restart, and skipping snapshots
  avoids stale evicted keys reappearing from a restored RDB file.

### Verifying it's working

```
docker exec redis redis-cli ping
# -> PONG

docker exec redis redis-cli INFO server
# confirm the process picked up redis.conf (e.g. check config values below)

docker exec redis redis-cli CONFIG GET maxmemory
docker exec redis redis-cli CONFIG GET maxmemory-policy
```
