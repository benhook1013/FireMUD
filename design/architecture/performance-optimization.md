# 🚀 Performance Optimization Guidelines

These notes summarize typical optimizations applied across FireMUD services.

## Database Queries

- Index common search fields such as `tenant_id` and foreign keys.
- Avoid N+1 queries using JPA entity graphs or join fetches.
- Prefer pagination for large result sets.

## Network Traffic

- gRPC calls use compression and keep-alive pings to reduce latency.
- HTTP responses are compressed via Spring Boot's built-in support.
- The Gateway applies connection pooling and caches static assets.
- Redis caches common lookups to reduce database load.

Following these patterns keeps resource usage low even as player counts grow.

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [System Architecture Overview](./system-architecture-overview.md)
