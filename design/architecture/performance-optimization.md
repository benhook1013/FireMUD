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
- Use Spring Cache backed by Redis for expensive queries.
- Database writes during gameplay are **deferred and batched**. The Game Session
  Service coordinates commits at the end of each tick so domain services only
  persists changes once per tick. This reduces write frequency and lock contention.
- The Entity Management Service uses optimistic locking with `@Version` columns
  on all entity tables to prevent lost updates.

## Runtime Processing

- Tick execution uses Redis (including Lua scripts) to stage, commit, and roll
  back commands atomically, minimizing network round trips and keeping state
  consistent across crashes. See
  [Tick System and Runtime Design](./system-architecture-ticks.md) for details.
- Tick regions execute independently so work can be parallelized across threads
  and servers for better scalability and fault isolation. Short‑lived Redis
  locks and retries avoid deadlocks and stalled regions.
- Automation and scripting run on their own schedule and inject commands into
  tick queues. Per‑script quotas and queue backpressure prevent runaway
  automation or infinite loops. See
  [Scripting Architecture](./system-architecture-scripting.md) for the detailed
  model.
- Redis runs with persistence and replication tuned for fast recovery so tick
  state can be replayed after failover (see
  [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)).
- Each tick enforces a soft execution budget and limits the number of
  commands/events processed to keep the game loop responsive; slow or
  conflicting work is deferred to later ticks instead of blocking the current
  frame.
- Central throttling (rate limits, per‑session limits) is enforced in the
  gameplay/session layer rather than at every edge, while all services expose
  Micrometer/Prometheus metrics so operators can monitor latency, throughput,
  and retry behavior.

## Network Traffic

- gRPC clients enable compression and keep-alive pings to reduce latency.
- HTTP response compression is enabled via Spring Boot.
- The Gateway applies HTTP client connection pooling and caches static assets
  using Spring's resource cache.
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
  (`room_cache_hits_total`, `room_cache_misses_total`). The Social Groups Service
  stores recent chat messages in Redis and records `chat_messages_published_total`.
  Older messages are persisted in PostgreSQL for long-term retrieval. Chat history
  caches expire based on message type:
  - **Says:** 2 hours or 50 messages per player
  - **Tells:** 48 hours or 50 messages per player
  - **Guild/City:** 48 hours or 50 messages per guild or city
  - **Account messages:** 48 hours or 50 messages
- High concurrency load tests with Gatling, located under `dev-tools/load-testing`, help determine scaling limits and guide database indexing improvements.

## Build Pipeline

- Gradle's **configuration cache** and **parallel execution** are enabled via
  `gradle.properties` to speed up local builds and CI workflows.
- GitHub Actions workflows make heavy use of Gradle and npm caching (including
  the Gradle user home and Node modules) to avoid redundant dependency
  downloads between CI runs.
- Separate build jobs for backend, frontend, and docs keep feedback fast and
  allow caching to be scoped to the tools they use.
- Heavy analysis tasks (SpotBugs, Checkstyle, JaCoCo, markdownlint, link
  checking) are gated behind the `fullCheck` flag so routine local builds stay
  quick while CI can still run the full suite when needed.

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [System Architecture Overview](./system-architecture-overview.md)
