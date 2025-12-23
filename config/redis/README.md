# Redis Configuration

Stores Redis config files used by the local Docker Compose stack:

- `redis.conf` (Coordination Redis): enables AOF persistence for coordination keys.
- `redis-cache.conf` (Cache/Rate-Limit Redis): disables persistence and enables eviction behavior for best-effort caches and rate limits.
