# 🚀 Performance Optimization Guidelines

These notes summarize typical optimizations applied across FireMUD services.

## Database Queries

- Index common search fields such as `tenant_id` and foreign keys. Migrations in
  each service create the needed indexes.
- Avoid N+1 queries using JPA entity graphs or join fetches. The Entity
  Management service uses `@EntityGraph` for inventory lookups.
- Prefer pagination for large result sets. **TODO:** current endpoints generally
  return full lists.

## Network Traffic

- gRPC calls should enable compression and keep-alive pings to reduce latency.
  These settings are not yet configured.
- Enable HTTP response compression via Spring Boot. Currently missing from the
  service configuration.
- The Gateway should apply connection pooling and cache static assets. There is
  no explicit configuration yet.
- Redis caches common lookups to reduce database load. The World Management and
  Social Groups services store hot rooms and recent chat messages in Redis with
  metrics (`room_cache_hits_total`, `room_cache_misses_total`,
  `chat_messages_published_total`).

Following these patterns keeps resource usage low even as player counts grow.

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [System Architecture Overview](./system-architecture-overview.md)
