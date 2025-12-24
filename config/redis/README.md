# Redis Configuration

Stores Redis config files used by the local Docker Compose stack.

These files are **local-dev defaults**, not production guidance. Kubernetes/production deployments typically use chart- or infra-managed Redis configuration and should follow the Redis durability and recovery posture described in `design/architecture/system-architecture-redis.md`.

- `redis.conf` (Coordination Redis, local dev)
  - Enables AOF persistence.
  - Disables RDB snapshots (`save ""`) to keep local disk usage and background snapshot overhead low.
- `redis-cache.conf` (Cache/Rate-Limit Redis, local dev)
  - Disables AOF persistence and disables RDB snapshots (`save ""`), since this role is explicitly best-effort.
  - Enables eviction behavior appropriate for caches and rate limits.

If you introduce additional Redis profiles (for example, production coordination clusters with RDB snapshots enabled), document them in the Redis architecture docs and keep this directory scoped to local Docker Compose defaults unless explicitly expanded.
