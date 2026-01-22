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
   - Ensure tick regions remain **HEALTHY** per the tick health rules in `design/architecture/system-architecture-tick-concepts-and-invariants.md` (for example, `tick.execution_time_ms_p95`/`p99` vs `tick_budget_ms` and lock TTLs) and that Redis tail-loss SLOs from `design/architecture/system-architecture-redis-operations.md` are not being violated.

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

## Tick- and Redis-Aware Scaling Indicators

When deciding **what** to scale, prefer signals tied to the tick model and Redis SLOs:

- Tick duration vs budget:
  - Watch `tick.execution_time_ms_p95` and `tick.execution_time_ms_p99` relative to `tick_interval_ms` and lock TTLs as described in `system-architecture-tick-concepts-and-invariants.md`.
  - If p99 execution time approaches the configured tick budget or a high fraction of lock TTL (for example, sustained `tick.execution_time_ms_p99 / lock_ttl_ms` near the “Degraded/Unsafe” thresholds), first reduce region density per Game Session instance or add Game Session replicas before changing tick cadence.
- Tail-loss envelopes:
  - Monitor `tail_loss_ms` / `tail_loss_ticks` and related Redis tail-loss SLO metrics from `system-architecture-redis-operations.md`.
  - If coordination tail-loss regularly exceeds the 1–2 second envelope (or roughly `≤ 2 × tick_interval_ms`), prioritize scaling or tuning **Coordination Redis** (hardware, AOF configuration, or shard layout) before adding more tick producers.
- Cross-region backlog:
  - Use `remote_followups_due_total`, `remote_followups_drain_lag_ms`, and `remote_followups_backlog_over_budget_total` from `system-architecture-tick-execution-flows.md` to decide whether target regions are draining remote work fast enough.
  - When these metrics stay elevated, consider increasing draining budgets for the affected regions or adjusting region layout; avoid unboundedly scaling origin regions that enqueue remote follow-ups.
- Retry and contention signals:
  - Track `tick_retry_queue_depth`, `tick_conflict_hotspot_detected_total`, and stalled-region indicators from the tick concepts/failures docs.
  - Persistent contention or stalled-progress alerts should drive **design or layout changes** (region boundaries, command costs) rather than only adding replicas.

Scaling decisions should be made against these metrics so that additional capacity actually improves tick health, tail-loss envelopes, and cross-region behavior instead of only shifting bottlenecks.
