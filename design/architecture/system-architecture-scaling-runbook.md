# FireMUD Scaling Runbook

This runbook describes how to **scale FireMUD services and infrastructure** to handle increased load while preserving stability and tick consistency.

For the conceptual overview of scaling, see `design/architecture/system-architecture-overview.md` and `design/architecture/system-architecture-ticks.md`.

## Scaling Principles

- Prefer **horizontal scaling** of stateless services (Gateway, TCP Proxy, game logic/stateless APIs).
- Ensure **Redis and PostgreSQL capacity** scales ahead of or alongside service replicas.
- Avoid sudden, large jumps in tick-region concurrency without monitoring Redis latency and database saturation.

## Pre-Scale Topology Decision Gate (Required)

Before applying scaling changes, classify the change:

- **Replica-only scaling**:
  - Adds/removes service replicas or resource limits without changing region boundaries or entity-to-region mapping.
  - Safe to execute with normal rollout and validation.
- **Topology-changing scaling**:
  - Any split/merge/re-home of regions, or any change that moves entities between region mappings.
  - Must acquire the deployment maintenance lock described in `system-architecture-redis-operations.md#maintenance-job-coordination` before freezing, fencing, or mutating region mappings. If restore recovery, reset, cleanup, migration, or exceptional backup-related maintenance is active, defer the topology change rather than overlapping control-plane operations. Routine online backup does not hold this coordination lock.
  - First implementation must use reset-first topology changes rather than in-place coordination-key migration unless a future topology migration slice explicitly ships the required rewrite tooling.
  - Target-state topology changes follow the topology-change protocol from `system-architecture-ticks.md`:
    1. Freeze/fence affected scope.
    2. Converge ledger outcomes.
    3. Bump `region_epoch` for mapping changes.
    4. Reset/rebuild coordination keys from durable state for first implementation; migrate coordination keys via shared tooling only after that tooling exists.
    5. Reconcile cross-region follow-ups.
    6. Resume.

Do not treat topology-changing scaling as a simple replica adjustment.

## Scaling Application Services

1. **Identify Hot Paths**
   - Use metrics and tracing to determine which services are under load.
   - Confirm whether the bottleneck is CPU, memory, I/O, or external dependencies.
2. **Adjust Deployment Replicas**
   - Increase `replicas` for the appropriate Kubernetes deployments (Gateway, Game Session, Game Logic, etc.).
   - Apply changes via Helm or Kustomize for the target environment.
3. **Validate Behavior**
   - Monitor request latency, error rates, and tick duration metrics.
   - Ensure tick regions remain in canonical non-incident states (`RUNNING` or bounded `DEGRADED`) per `design/architecture/system-architecture-tick-concepts-and-invariants.md` and that Redis tail-loss SLOs from `design/architecture/system-architecture-redis-operations.md` are not being violated.

## Scaling Redis

Refer to `design/architecture/system-architecture-redis.md` and `design/architecture/system-architecture-redis-operations.md` for detailed Redis scaling strategies.

Key steps:

- Scale **Coordination Redis** very conservatively; prioritize predictable latency over cache size.
- Scale **Cache/Rate-Limit Redis** based on hit rates, memory usage, and eviction patterns.
- Use Redis cluster or sharding only where documented and tested; follow the shard discipline and key naming guidance from the Redis architecture docs.

## Scaling PostgreSQL

- Treat PostgreSQL as a primary scaling boundary for the tick system, not only as a backing store:
  - Tick execution writes tick-batch rows, effect-ledger rows, region status updates, remote follow-up claims, and reconciliation backlog state.
  - Replay and recovery add their own write/read pressure through replay scans, backlog retries, and operator-driven reconcile flows.
- Use read replicas for read-heavy workloads where supported by the design, but do not assume replicas solve tick-path pressure; the primary write path must be sized for peak tick and replay throughput.
- Increase instance size or provisioned IOPS as necessary, following database operations runbooks.
- Monitor Slow Query logs and apply schema/index optimizations as needed.
- Partition and retention strategy must be explicit for the full high-churn tick-history surface:
  - tick effect ledger / tick-batch tables
  - cross-region follow-up tables
  - effect reconciliation backlog tables
  - command ingress / command outcome status tables keyed by `(tenantId, gameInstanceId, commandId)`
- Treat these as one retention policy surface during capacity review:
  - define the retention horizon for each family,
  - define the partitioning scheme or archive strategy,
  - define vacuum/GC cadence,
  - confirm the command-status retention window outlives expected player/client retry windows,
  - verify oldest-pending-row age and write-latency SLOs across the whole surface rather than table-by-table in isolation.
  - confirm dashboards and operator playbooks still map command-status rows onto the canonical terminal-state vocabulary in `design/architecture/system-architecture-tick-execution-flows.md`.

## Verification

- After scaling changes, re-run smoke tests and a subset of load tests.
- Confirm that Redis and database latency remain within acceptable bounds.

## Tick- and Redis-Aware Scaling Indicators

When deciding **what** to scale, prefer signals tied to the tick model and Redis SLOs:

- Tick duration vs budget (primary safety ratio):
  - Watch `tick_execution_time_ms_p95` and `tick_execution_time_ms_p99` (recording rules derived from `tick_execution_time_ms_bucket`) relative to **lock TTLs** as described in `system-architecture-tick-concepts-and-invariants.md` (that is, `tick_execution_time_ms_p99 / tick_lock_ttl_ms`).
  - Treat `tick_execution_time_ms_p99 / tick_lock_ttl_ms` as the primary safety ratio for tick runtime; regions that sustain ratios near `DEGRADED`/`STALLED` transition thresholds from the concepts doc should first reduce region density per Game Session instance or add Game Session replicas before changing tick cadence.
  - For intuition, you may also track `tick_execution_time_ms_p99 / tick_interval_ms`, but decisions should be grounded in the TTL-based ratio since `lock_ttl_ms` is derived from `tick_interval_ms` via the canonical formulas.
  - Treat any `tick_interval_ms` change as a topology-level/runtime-contract change for the affected live `regionEpoch`, not as a harmless tuning knob. If cadence changes would alter timer ordering normalization, perform them with an epoch bump and timer re-derivation as required by the tick invariants.
  - Example: moving a live region from `100ms` cadence to `200ms` cadence requires pause, epoch bump, timer `due_tick_id` re-derivation, and resume on the new epoch; it is not an in-place tuning-only change.
- Tail-loss envelopes:
  - Monitor tail-loss metrics such as `redis_coordination_tail_loss_ms{scope}` and related Redis tail-loss SLO metrics from `system-architecture-redis-operations.md`.
  - If coordination tail-loss regularly exceeds `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`, prioritize scaling or tuning **Coordination Redis** (hardware, AOF configuration, or shard layout) before adding more tick producers.
- Cross-region backlog:
  - Use `remote_followups_due_total`, `remote_followups_drain_lag_ms`, and `remote_followups_backlog_over_budget_total` from `system-architecture-tick-execution-flows.md` to decide whether target regions are draining remote work fast enough.
  - Use Game Session runtime ownership/control-plane reads for region-specific backlog diagnosis; these Prometheus series are aggregate process signals and must not regain raw tenant/game-instance/region labels.
  - When these metrics stay elevated, consider increasing draining budgets for the affected regions or adjusting region layout; avoid unboundedly scaling origin regions that enqueue remote follow-ups.
- Retry and contention signals:
  - Track `tick_retry_queue_depth`, `tick_conflict_hotspot_detected_total`, and stalled-region indicators from the tick concepts/failures docs.
  - Persistent contention or stalled-progress alerts should drive **design or layout changes** (region boundaries, command costs) rather than only adding replicas.

Scaling decisions should be made against these metrics so that additional capacity actually improves tick health, tail-loss envelopes, and cross-region behavior instead of only shifting bottlenecks.

## Starting Guardrails (Baseline Sizing)

The exact safe limits for a deployment depend on hardware and tuning, but the following **baseline guardrails** provide a starting point that aligns with the tick and Redis SLOs. They are intentionally conservative and should be validated and adjusted via load tests:

- **Per-Game Session instance region density**
  - For tick intervals around `100–250ms`, start with **no more than 50–100 active regions** per Game Session pod.
  - If `tick_execution_time_ms_p99 / tick_lock_ttl_ms` regularly approaches the canonical `DEGRADED`/`STALLED` thresholds from the tick concepts doc for any region, treat that as a signal to reduce regions per pod or increase pod resources before tightening tick cadence.
- **Per-region coordination load**
  - Aim for `tick:{tenantRegionTag}:pending` to represent at most **one in-flight tick** plus a small buffer of staged work; thousands of uncommitted effects for a single region should be treated as an anomaly and investigated.
  - Keep `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` counts per region within the “tens of thousands” envelope from the Redis operations doc; sustained higher values usually indicate that timers or retries are being used as data stores rather than scheduling hints.
- **Redis tail-loss envelope**
  - Size Coordination Redis so that measured `redis_coordination_tail_loss_ms` remains within `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` under expected peak load.
  - If tail-loss regularly exceeds that envelope after scaling application services, prioritize Coordination Redis capacity (CPU, memory, AOF layout) or region density before adding more tick producers.

## Capacity Model (Required Inputs)

Baseline guardrails are only a starting point. Before materially increasing region density, estimate capacity using a simple per-pod model:

- `pod_tick_cost_ms = active_regions * (p99_region_tick_ms + p99_remote_drain_ms + p99_replay_overhead_ms)`
- Keep `pod_tick_cost_ms` below the effective scheduling envelope implied by `tick_interval_ms`, lock TTL headroom, and observed Redis script latency.
- When `solo_tick_budget_ms` is enabled for `requiresSoloTick` commands, capacity reviews must also model the isolated solo-tick path and its derived TTL/health thresholds rather than treating those commands as ordinary ticks.
- Calibrate each term from load tests in the target profile (`dev_local`, `hobby_self_hosted`, `production_clustered`) and record:
  - `p99_region_tick_ms` from `tick_execution_time_ms_p99`.
  - `p99_remote_drain_ms` from remote follow-up lag/drain metrics.
  - `p99_replay_overhead_ms` from replay-controller and tick replay metrics.
- PostgreSQL capacity inputs are required alongside the pod cost model. Load tests and environment docs should record at minimum:
  - tick-batch and effect-ledger inserts/updates per second
  - remote follow-up claim/update QPS
  - replay-controller scan/update QPS
  - effect-reconciliation backlog retry QPS
  - command-ingress and command-status update QPS
  - p95/p99 write latency for the primary tick-path tables
  - retention horizon, partitioning scheme, and vacuum/GC cadence for high-churn tables

Scaling decisions must not rely only on Redis tail-loss and pod density signals. If ledger age, replay scan lag, follow-up claim latency, or backlog-table bloat is rising, treat PostgreSQL as the bottleneck and scale or redesign there before increasing tick concurrency.

Scaling plans should include this calibration so “add replicas” and “increase regions per pod” decisions are tied to measured tick and coordination cost, not only static guardrail numbers.

Environment docs and load-test reports should record any deviations from these starting numbers along with the observed tick and tail-loss metrics so operators can make informed scaling decisions in future iterations.
