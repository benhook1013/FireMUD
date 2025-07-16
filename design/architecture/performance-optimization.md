# 🚀 Performance Optimization Guidelines

These notes summarize typical optimizations applied across FireMUD services.

## Database Queries

- Index common search fields such as `tenant_id` and foreign keys. Several
  services add indexes in Flyway migrations (for example the saga tables and game
  asset tables). Additional indexes should be added where missing.
- Avoid N+1 queries using JPA entity graphs or join fetches. The Entity
  Management service uses `@EntityGraph` for inventory lookups.
- Prefer pagination for large result sets. Core services expose pageable
  endpoints so huge lists are avoided.
- Use Spring Cache backed by Redis for expensive queries. The Entity Management
  service caches character inventory graphs and the World Management service
  caches hot rooms with TTL-based eviction.
- Database writes during gameplay are **deferred and batched**. The Game Session
  Service coordinates commits at the end of each tick so the Entity Management
  Service only persists changes once per tick. This reduces write frequency and
  lock contention. (TODO: Not yet implemented)
- The Entity Management Service uses optimistic locking with `@Version` columns
  on all entity tables to prevent lost updates.

## Runtime Processing

- Tick execution in the Game Session Service relies on Redis Lua scripts for
  atomic command staging, commit and rollback. This minimizes network round
  trips and guarantees consistent state across crashes. See
  [Tick System and Runtime Design](./system-architecture-ticks.md) for
  details.
- Distributed tick locks in Redis use short TTLs to prevent deadlocks. Failed
  actions are rolled back and retried automatically.
- Tick regions execute independently so work can be parallelized across
  threads and servers for better scalability and fault isolation.
- The Automation & Scripting Service evaluates scripts on its own schedule and
  injects resulting commands into tick queues. Per-script quotas are enforced
  via Redis before queuing to avoid runaway automation.
  Quota enforcement metrics (`script_quota_allowed_total`,
  `script_quota_denied_total`) and automation queue metrics
  (`automation_queue_enqueued_total`, `automation_queue_drained_total`) provide
  visibility into heavy script load.
- Redis exporters publish Lua latency, lock contention and retry queue depth
  metrics so operators can spot hotspots in Grafana dashboards.
- The Game Session Service exposes `tick_retry_queue_depth`,
  `tick_requeued_action_total`, and `tick_retry_backoff_count_total` metrics for
  per-region visibility into retries and backoff behavior.
- Graceful degradation logic in the Game Session Service retries failed
  Redis operations so stalled ticks do not block gameplay.
- The Game Session Service records `game_session_commands_enqueued_total` and
  `game_session_tick_duration_ms` metrics so operators can monitor throughput
  and tick performance.
- Service methods across all microservices use `@Timed` annotations so
  Prometheus can track request latency and call frequency.
- Redis runs with **AOF persistence** and synchronous replication via `WAIT`
  so tick state can be recovered quickly after failover. The Game Session
  Service automatically replays staged commands on restart.
- Each tick enforces a **soft execution budget** (~100ms). Slow actions are
  deferred to follow-up ticks so long-running commands never block the game
  loop. Conflict metadata collected during retries highlights hotspots for
  operators. (TODO: Not yet implemented)
- Lua staging scripts move only a limited number of commands or events per tick
  (configurable via `game.tick-max-commands` and `automation.tick-max-events`).
  This prevents runaway loops and keeps work evenly distributed across ticks.
- The TCP Proxy Service limits connections per IP and throttles messages per
  client to shield the gateway from abuse.

## Network Traffic

- gRPC calls enable compression and keep-alive pings to reduce latency.
- HTTP response compression is enabled via Spring Boot.
- The Gateway applies connection pooling and caches static assets.
- gRPC and REST endpoints are instrumented with Micrometer metrics and
  OpenTelemetry tracing using shared interceptors to monitor latency and error
  rates.
- Spring Cloud Gateway applies Redis-backed request rate limiting to protect
  services from sudden spikes.
- The TCP Proxy Service buffers Telnet input and applies connection
  throttling before forwarding traffic to the Gateway, preventing sudden client
  bursts from overwhelming the backend.
- Gateway connection metrics (`gateway.connections.total` and
  `gateway.connections.active`) help operators track usage and capacity.
- Redis caches common lookups to reduce database load. The World Management
  service stores hot rooms with configurable TTL and hit/miss metrics
  (`room_cache_hits_total`, `room_cache_misses_total`). The Social Groups service
  stores recent chat messages in Redis and records `chat_messages_published_total`.
  Chat history caches expire based on message type:
  - **Says:** 2 hours or 50 messages per player
  - **Tells:** 48 hours or 50 messages per player
  - **Guild/City:** 48 hours or 50 messages per guild or city
  - **Account messages:** 48 hours or 50 messages
  Older messages are persisted in PostgreSQL for long-term retrieval.
- High concurrency load tests with Gatling, located under `dev-tools/load-testing`, help determine scaling limits and guide database indexing improvements.

Following these patterns keeps resource usage low even as player counts grow.

## Build Pipeline

- Gradle's **configuration cache** and **parallel execution** are enabled via
  `gradle.properties` to speed up local builds and CI workflows.

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [System Architecture Overview](./system-architecture-overview.md)
