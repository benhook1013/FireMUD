# 🚀 Performance Optimization Guidelines

These notes summarize typical optimizations applied across FireMUD services.

## Database Queries

- Index common search fields such as `tenant_id` and foreign keys. Several
  services add indexes in Flyway migrations (for example the saga tables and game
  asset tables). Additional indexes should be added where missing.
- Avoid N+1 queries using JPA entity graphs or join fetches. The Entity
  Management service uses `@EntityGraph` for inventory lookups.
- Prefer pagination for large result sets. **TODO:** current endpoints generally
  return full lists.

- Use Spring Cache backed by Redis for expensive queries. The Entity Management
  service caches character inventory graphs and the World Management service
  caches hot rooms with TTL-based eviction.
- Database writes during gameplay are **deferred and batched**. The Game Session
  Service coordinates commits at the end of each tick so the Entity Management
  Service only persists changes once per tick. This reduces write frequency and
  lock contention.
- The Entity Management Service design calls for optimistic locking on entity
  tables, but version columns have not yet been implemented.

## Runtime Processing

- Tick execution in the Game Session Service relies on Redis Lua scripts for
  atomic command staging, commit and rollback. This minimizes network round
  trips and guarantees consistent state across crashes. See
  [Tick System and Runtime Design](./system-architecture-ticks.md) for
  details.
- The Automation & Scripting Service batches NPC logic in tick cycles and
  enforces per-script quotas via Redis to avoid runaway automation.
  Quota enforcement metrics (`script_quota_allowed_total`,
  `script_quota_denied_total`) and automation queue metrics
  (`automation_queue_enqueued_total`, `automation_queue_drained_total`) provide
  visibility into heavy script load.
- Redis exporters publish Lua latency, lock contention and retry queue depth
  metrics so operators can spot hotspots in Grafana dashboards.
- The Game Session Service records `game_session_commands_enqueued_total` and
  `game_session_tick_duration_ms` metrics so operators can monitor throughput
  and tick performance.

## Network Traffic

- gRPC calls should enable compression and keep-alive pings to reduce latency.
  These settings are not yet configured.
- Enable HTTP response compression via Spring Boot. Currently missing from the
  service configuration.
- The Gateway should apply connection pooling and cache static assets. There is
  no explicit configuration yet.
- gRPC and REST endpoints are instrumented with Micrometer metrics and
  OpenTelemetry tracing using shared interceptors to monitor latency and error
  rates.
- Spring Cloud Gateway applies Redis-backed request rate limiting to protect
  services from sudden spikes.
- Redis caches common lookups to reduce database load. The World Management
  service stores hot rooms with configurable TTL and hit/miss metrics
  (`room_cache_hits_total`, `room_cache_misses_total`). The Social Groups service
  stores recent chat messages in Redis and records `chat_messages_published_total`.
  A cache expiration policy for chat history is still TODO.

Following these patterns keeps resource usage low even as player counts grow.

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [System Architecture Overview](./system-architecture-overview.md)
