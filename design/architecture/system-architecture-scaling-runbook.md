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
   - Ensure tick regions remain **HEALTHY** per the tick health rules in `design/architecture/system-architecture-tick-concepts-and-invariants.md` (for example, `tick_execution_time_ms_p95`/`tick_execution_time_ms_p99` and `tick_execution_time_ms_p99 / tick_lock_ttl_ms`) and that Redis tail-loss SLOs from `design/architecture/system-architecture-redis-operations.md` are not being violated.

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

- Tick duration vs budget (primary safety ratio):
  - Watch `tick_execution_time_ms_p95` and `tick_execution_time_ms_p99` (recording rules derived from `tick_execution_time_ms_bucket`) relative to **lock TTLs** as described in `system-architecture-tick-concepts-and-invariants.md` (that is, `tick_execution_time_ms_p99 / tick_lock_ttl_ms`).
  - Treat `tick_execution_time_ms_p99 / tick_lock_ttl_ms` as the primary safety ratio for tick runtime; regions that sustain ratios near the “Degraded/Unsafe” thresholds in the concepts doc should first reduce region density per Game Session instance or add Game Session replicas before changing tick cadence.
  - For intuition, you may also track `tick_execution_time_ms_p99 / tick_interval_ms`, but decisions should be grounded in the TTL-based ratio since `lock_ttl_ms` is derived from `tick_interval_ms` via the canonical formulas.
- Tail-loss envelopes:
  - Monitor tail-loss metrics such as `redis_coordination_tail_loss_ms{tenantId,regionId}` and related Redis tail-loss SLO metrics from `system-architecture-redis-operations.md`.
  - If coordination tail-loss regularly exceeds the 1–2 second envelope (or roughly `≤ 2 × tick_interval_ms`), prioritize scaling or tuning **Coordination Redis** (hardware, AOF configuration, or shard layout) before adding more tick producers.
- Cross-region backlog:
  - Use `remote_followups_due_total`, `remote_followups_drain_lag_ms`, and `remote_followups_backlog_over_budget_total` from `system-architecture-tick-execution-flows.md` to decide whether target regions are draining remote work fast enough.
  - When these metrics stay elevated, consider increasing draining budgets for the affected regions or adjusting region layout; avoid unboundedly scaling origin regions that enqueue remote follow-ups.
- Retry and contention signals:
  - Track `tick_retry_queue_depth`, `tick_conflict_hotspot_detected_total`, and stalled-region indicators from the tick concepts/failures docs.
  - Persistent contention or stalled-progress alerts should drive **design or layout changes** (region boundaries, command costs) rather than only adding replicas.

Scaling decisions should be made against these metrics so that additional capacity actually improves tick health, tail-loss envelopes, and cross-region behavior instead of only shifting bottlenecks.

## Starting Guardrails (Baseline Sizing)

The exact safe limits for a deployment depend on hardware and tuning, but the following **baseline guardrails** provide a starting point that aligns with the tick and Redis SLOs. They are intentionally conservative and should be validated and adjusted via load tests:

- **Per-Game Session instance region density**
  - For tick intervals around `100–250ms`, start with **no more than 50–100 active regions** per Game Session pod.
  - If `tick_execution_time_ms_p99 / tick_lock_ttl_ms` regularly approaches the Degraded/Unsafe thresholds from the tick concepts doc for any region, treat that as a signal to reduce regions per pod or increase pod resources before tightening tick cadence.
- **Per-region coordination load**
  - Aim for `tick:{tenantRegionTag}:pending` to represent at most **one in-flight tick** plus a small buffer of staged work; thousands of uncommitted effects for a single region should be treated as an anomaly and investigated.
  - Keep `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` counts per region within the “tens of thousands” envelope from the Redis operations doc; sustained higher values usually indicate that timers or retries are being used as data stores rather than scheduling hints.
- **Redis tail-loss envelope**
  - Size Coordination Redis so that measured `redis_coordination_tail_loss_ms` remains within the 1–2 second envelope (or roughly `≤ 2 × tick_interval_ms`) under expected peak load.
  - If tail-loss regularly exceeds that envelope after scaling application services, prioritize Coordination Redis capacity (CPU, memory, AOF layout) or region density before adding more tick producers.

Environment docs and load-test reports should record any deviations from these starting numbers along with the observed tick and tail-loss metrics so operators can make informed scaling decisions in future iterations.
