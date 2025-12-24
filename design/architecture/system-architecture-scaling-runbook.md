# FireMUD Scaling Runbook

This runbook describes how to **scale FireMUD services and infrastructure** to handle increased load while preserving stability and tick consistency.

For the conceptual overview of scaling, see `design/architecture/system-architecture-overview.md` and `design/architecture/system-architecture-ticks.md`.

## Scaling Principles

- Prefer **horizontal scaling** of stateless services (Gateway, TCP Proxy, game logic/stateless APIs).
- Ensure **Redis and PostgreSQL capacity** scales ahead of or alongside service replicas.
- Avoid sudden, large jumps in tick-region concurrency without monitoring Redis latency and database saturation.

## Scaling Application Services

1. **Identify Hot Paths**
   - Use metrics and tracing to determine which services are under load.
   - Confirm whether the bottleneck is CPU, memory, I/O, or external dependencies.
2. **Adjust Deployment Replicas**
   - Increase `replicas` for the appropriate Kubernetes deployments (Gateway, Game Session, Game Logic, etc.).
   - Apply changes via Helm or Kustomize for the target environment.
3. **Validate Behavior**
   - Monitor request latency, error rates, and tick duration metrics.
   - Ensure tick regions do not exceed the safe capacity guidelines described in `design/architecture/system-architecture-ticks.md`.

## Scaling Redis

Refer to `design/architecture/system-architecture-redis.md` and `design/architecture/system-architecture-redis-operations.md` for detailed Redis scaling strategies.

Key steps:

- Scale **Coordination Redis** very conservatively; prioritize predictable latency over cache size.
- Scale **Cache/Rate-Limit Redis** based on hit rates, memory usage, and eviction patterns.
- Use Redis cluster or sharding only where documented and tested; follow the shard discipline and key naming guidance from the Redis architecture docs.

## Scaling PostgreSQL

- Use read replicas for read-heavy workloads where supported by the design.
- Increase instance size or provisioned IOPS as necessary, following database operations runbooks.
- Monitor Slow Query logs and apply schema/index optimizations as needed.

## Verification

- After scaling changes, re-run smoke tests and a subset of load tests.
- Confirm that Redis and database latency remain within acceptable bounds.

