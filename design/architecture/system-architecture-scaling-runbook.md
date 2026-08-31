# FireMUD Scaling Runbook

This runbook describes how to **scale FireMUD services and infrastructure** to handle increased load while preserving stability and tick consistency.

For the conceptual overview of scaling, see `design/architecture/system-architecture-overview.md` and `design/architecture/system-architecture-ticks.md`.

Validation and runtime-proof selection for changes to this runbook follows the shared [Validation and Runtime Proof workflow](../developer-workflows/validation-and-runtime-proof.md); record execution results in PR/CI evidence or the owning implementation tracker.

## Implementation Status

The shared retention-class declarations, cross-service horizon compatibility and blockers, safe-watermark cleanup, holds, and complete focused proof remain unimplemented; core Game Session tick, command, and remote-work tables are unpartitioned and lack coordinated retention, while some service-local sweeps exist. The owning status record is [Shared Runtime, Service Contracts, and Persistence — `SF-2.1`](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md#capability-status). The [Data Retention and High-Churn Tables](#data-retention-and-high-churn-tables) section below defines target obligations under ADR 0163; do not treat those obligations, or any measured envelope below, as proof that the shared retention contract is live.

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
   - Ensure tick regions remain in canonical non-incident states (`RUNNING` or bounded `DEGRADED`) per `design/architecture/system-architecture-tick-concepts-and-invariants.md`. For profiles eligible to claim the measured Redis coordination-write exposure SLO, confirm the SLO from `design/architecture/system-architecture-redis-operations.md` is not violated; ephemeral preview/CI opt-outs validate reset tolerance and latency instead.

## Scaling Redis

Refer to `design/architecture/system-architecture-redis.md` and `design/architecture/system-architecture-redis-operations.md` for detailed Redis scaling strategies.

Key steps:

- Scale **Coordination Redis** very conservatively; prioritize predictable latency over cache size.
- Scale **Cache/Rate-Limit Redis** based on hit rates, memory usage, and eviction patterns.
- Use Redis cluster or sharding only where documented and tested; follow the shard discipline and key naming guidance from the Redis architecture docs.

## Scaling PostgreSQL

- Treat PostgreSQL as a primary scaling boundary for the tick system, not only as a backing store:
  - **Current live:** Tick execution writes `tick_batch` and effect-ledger (`tick_effect`) rows and advances the owner-fenced `runtime_region_status` row once per committed tick (including its last-committed tick and update timestamp). These are implemented durable PostgreSQL writes that must be included in primary-write capacity estimates.
  - **Current live:** Remote-follow-up scheduling writes coordinator/follow-up state, and the tick path claims due `remote_followup` rows and updates their queue/status state while draining them; result and timeout reconciliation also updates the existing follow-up/coordinator/result rows. Size this workload from both origin scheduling and per-tick claim/update/reconciliation rates, not only from `tick_batch` and `tick_effect` volume.
  - **Target-state conditional:** Any additional effect-reconciliation backlog schema or API beyond the existing remote-follow-up/result state remains a future persistence surface and must be sized when that design is implemented; it is not current deployment evidence.
  - **Target-state conditional:** Future replay/recovery scans and any new operator-driven reconciliation flows add their own read/write pressure when implemented. Existing remote-follow-up draining and result/timeout reconciliation are current paths and are not examples of an unimplemented target surface; see [Game Session API status](./microservices/game-session-service/api-contracts.md#implementation-status).
- Use read replicas for read-heavy workloads where supported by the design, but do not assume replicas solve tick-path pressure; the primary write path must be sized for peak tick and replay throughput.
- Increase instance size or provisioned IOPS as necessary, following database operations runbooks.
- Monitor Slow Query logs and apply schema/index optimizations as needed.

### Data Retention and High-Churn Tables

The following are **target obligations** under [ADR 0163](./decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md), not evidence that the current deployment has already implemented a shared retention policy:

- Partition and retention strategy must be explicit for the full high-churn tick-history surface:
  - tick effect ledger / tick-batch tables
  - cross-region follow-up tables
  - effect reconciliation backlog tables
  - command ingress / command outcome status tables keyed by `(tenantId, gameInstanceId, commandId)`
- Treat these as one cross-service retention-class and compatibility surface during capacity review, while each service remains responsible for its own schema and cleanup:
  - classify live or recoverable work, retry/idempotency receipts, recovery/reconciliation lineage, purpose-bound audit or safety evidence, and diagnostic/content payload separately;
  - never age-delete nonterminal, inconsistent, quarantined, or still-actionable recovery work;
  - define each family's terminality and safe-watermark predicate, blocking references, horizon, partition/compaction/archive strategy, vacuum/GC cadence, hold behavior, and bounded metrics;
  - preserve compact consumer receipts and effect guards through every producer/client retry, duplicate-delivery, replay, restore, and reconciliation window that can address the logical action;
  - permit bulky payload or diagnostic detail to expire earlier than the minimum correctness receipt only after replay, investigation, and governance no longer require it;
  - drop a partition only when every row is eligible, otherwise move protected rows or use a bounded row-level strategy;
  - verify oldest-blocking-row age, cleanup lag, write latency, storage growth, and dependency inequalities across the whole surface rather than table-by-table in isolation; and
  - confirm dashboards and operator playbooks still map command-status rows onto the canonical terminal-state vocabulary in `design/architecture/system-architecture-tick-execution-flows.md`.

Exact durations are deployment policy derived from declared retry, recovery, and governance horizons and measured growth, not one platform-wide constant. See [ADR 0163](./decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md).

## Verification

- After scaling changes, re-run smoke tests and a subset of load tests.
- Confirm that Redis and database latency remain within acceptable bounds.

## Tick- and Redis-Aware Scaling Indicators

When deciding **what** to scale, prefer signals tied to the tick model and Redis SLOs:

- Tick duration vs budget (primary safety ratio):
  - Watch `tick_execution_time_ms_p95{scope_class}` and `tick_execution_time_ms_p99{scope_class}` (recording rules derived from `tick_execution_time_ms_bucket{scope_class,le}`) relative to **lock TTLs** as described in `system-architecture-tick-concepts-and-invariants.md` (that is, `tick_execution_time_ms_p99{scope_class} / tick_lock_ttl_ms{scope_class}`). These are bounded class-level rollups, not selectors for an individual region.
  - Treat `tick_execution_time_ms_p99{scope_class} / tick_lock_ttl_ms{scope_class}` as the primary detection/escalation signal for tick runtime pressure. When a rollup sustains a ratio near the `DEGRADED`/`STALLED` thresholds, resolve the exact affected regions through control-plane/runtime-health evidence, then first reduce region density per Game Session instance or add Game Session replicas before changing tick cadence.
  - For intuition, you may also track `tick_execution_time_ms_p99{scope_class} / tick_interval_ms{scope_class}`, but decisions should be grounded in the TTL-based ratio because production `lock_ttl_ms` is the shared resolver's evidence-calibrated setting. The interval-based relationship is a bootstrap default only, not a production TTL derivation.
  - Treat any `tick_interval_ms` change as a topology-level/runtime-contract change for the affected live `regionEpoch`, not as a harmless tuning knob. If cadence changes would alter timer ordering normalization, perform them with an epoch bump and timer re-derivation as required by the tick invariants.
  - Example: moving a live region from `100ms` cadence to `200ms` cadence requires pause, epoch bump, timer `due_tick_id` re-derivation, and resume on the new epoch; it is not an in-place tuning-only change.
- Coordination-write exposure envelopes:
  - **Profiles that support and claim the measured envelope:** validate measured Coordination Redis unreplicated-write exposure against `redis_unreplicated_write_window_slo_ms` under [Redis operations](./system-architecture-redis-operations.md).
  - **Profiles that omit the measured envelope:** record that omission explicitly in profile/evidence; omission is not a zero-valued measurement or an SLO pass. Validate reset tolerance and latency instead, while still requiring canonical `RUNNING` or bounded `DEGRADED` region status. Ephemeral preview/CI profiles are examples of this opt-out posture.
  - For eligible profiles, monitor measured Coordination Redis unreplicated-write exposure against `redis_unreplicated_write_window_slo_ms`; use the [Redis metrics catalog](./system-architecture-redis-metrics-catalog.md) for metric definitions and [Redis operations](./system-architecture-redis-operations.md) for operational response/SLO procedures.
  - If measured unreplicated-write exposure regularly exceeds `redis_unreplicated_write_window_slo_ms`, prioritize scaling or tuning **Coordination Redis** (hardware, AOF configuration, or shard layout) before adding more tick producers.
- Cross-region backlog:
  - Use `remote_followups_due_total`, `remote_followups_drain_lag_ms`, and `remote_followups_backlog_over_budget_total` from `system-architecture-tick-execution-flows.md` to decide whether target regions are draining remote work fast enough.
  - Use Game Session runtime ownership/control-plane reads for region-specific backlog diagnosis; these Prometheus series are aggregate process signals and must not regain raw tenant/game-instance/region labels.
  - When these metrics stay elevated, consider increasing draining budgets for the affected regions or adjusting region layout; avoid unboundedly scaling origin regions that enqueue remote follow-ups.
- Retry and contention signals:
  - Track `tick_retry_queue_depth`, `tick_conflict_hotspot_detected_total`, and stalled-region indicators from the tick concepts/failures docs.
  - Persistent contention or stalled-progress alerts should drive **design or layout changes** (region boundaries, command costs) rather than only adding replicas.

Scaling decisions should be made against these metrics so that additional capacity actually improves tick health, coordination-write exposure envelopes, and cross-region behavior instead of only shifting bottlenecks.

## Starting Guardrails (Baseline Sizing)

The exact safe limits for a deployment depend on hardware and tuning, but the following **baseline guardrails** provide a starting point that aligns with the tick and Redis SLOs. They are intentionally conservative and should be validated and adjusted via load tests:

- **Per-Game Session instance region density**
  - For tick intervals around `100–250ms`, start with **no more than 50–100 active regions** per Game Session pod.
  - If the class-level `tick_execution_time_ms_p99{scope_class} / tick_lock_ttl_ms{scope_class}` rollup regularly approaches the canonical `DEGRADED`/`STALLED` thresholds, use authoritative runtime-health records to identify the affected regions, then treat the pressure as a signal to reduce regions per pod or increase pod resources before tightening tick cadence.
- **Per-region coordination load**
  - Aim for `tick:{tenantRegionTag}:pending` to represent at most **one in-flight tick** plus a small buffer of staged work; thousands of uncommitted effects for a single region should be treated as an anomaly and investigated.
  - Keep `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` counts per region within the “tens of thousands” envelope from the Redis operations doc; sustained higher values usually indicate that timers or retries are being used as data stores rather than scheduling hints.
- **Redis unreplicated-write exposure envelope**
  - **Target obligation for profiles that support and claim the measured envelope:** size Coordination Redis so that measured unreplicated-write exposure remains within `redis_unreplicated_write_window_slo_ms` under expected peak load.
  - **Target obligation for profiles that omit the measured envelope:** retain an explicit omission plus reset-tolerance and latency evidence; do not substitute a zero or claim the measured SLO.
  - If measured unreplicated-write exposure regularly exceeds that envelope after scaling application services, prioritize Coordination Redis capacity (CPU, memory, AOF layout) or region density before adding more tick producers.

## Capacity Model (Required Inputs)

Baseline guardrails are only a starting point. Before materially increasing region density, estimate capacity using a simple per-pod model:

- `pod_tick_cost_ms = active_regions * (p99_region_tick_ms + p99_remote_drain_ms + p99_replay_overhead_ms)`
- Keep `pod_tick_cost_ms` below the effective scheduling envelope implied by `tick_interval_ms`, lock TTL headroom, and observed Redis script latency.
- When `solo_tick_budget_ms` is enabled for `requiresSoloTick` commands, capacity reviews must also model the isolated solo-tick path and its derived TTL/health thresholds rather than treating those commands as ordinary ticks.

This formula is a conservative first-pass input, not a complete capacity predictor. It must not be interpreted as proof that all region work executes serially or that summing independent p99 values predicts the p99 of the combined workload. Calibration must additionally account for:

- available CPU, executor/thread-pool parallelism, scheduling contention, and whether region ticks synchronize or stagger;
- players, entities, scripts, timers, and retries per region;
- representative command mix, chat and other fan-out, and authored high-cost or solo-tick commands;
- Redis latency, script throughput, memory, persistence, and unreplicated-write exposure behavior;
- PostgreSQL read/write throughput, latency, storage growth, vacuum/GC, and retention behavior;
- cross-region follow-up volume, target-drain behavior, and topology;
- replay, recovery, dependency failure, retry, and reconciliation load;
- process and node memory, garbage collection, network throughput, connection pressure, and required operating headroom.

- Calibrate each term from load tests in the target profile (`dev_local`, `hobby_self_hosted`, `production_clustered`) and record:
  - `p99_region_tick_ms` from load-test measurements and exact per-region runtime-health evidence; the bounded `tick_execution_time_ms_p99{scope_class}` rollup is corroborating pressure only and cannot identify a region’s p99.
  - `p99_remote_drain_ms` from remote follow-up lag/drain metrics.
  - `p99_replay_overhead_ms` from replay-controller and tick replay metrics when the replay controller and those metrics are implemented and emitted.
- PostgreSQL capacity inputs are required alongside the pod cost model. Load tests and environment docs should record at minimum:
  - tick-batch and effect-ledger inserts/updates per second
  - remote follow-up claim/update QPS
  - replay-controller scan/update QPS when the replay controller and its metrics are implemented and emitted
  - target-state conditional effect-reconciliation backlog retry QPS, once a separate effect-reconciliation backlog surface is implemented
  - command-ingress and command-status update QPS
  - p95/p99 write latency for the primary tick-path tables
  - retention horizon, partitioning scheme, and vacuum/GC cadence for high-churn tables

Scaling decisions must not rely only on Redis unreplicated-write exposure and pod density signals. For implemented durable paths, rising ledger age or follow-up claim latency should be treated as a PostgreSQL bottleneck and should prompt scaling or redesign there before increasing tick concurrency. Rising replay scan lag is a comparable signal only when the replay controller is implemented and the corresponding metric is emitted. Effect-reconciliation-backlog bloat is a conditional target-state signal only after a separate backlog table/API is implemented; its absence in the current deployment is not a zero-valued measurement or evidence that the backlog is healthy.

Scaling plans should include this calibration so “add replicas” and “increase regions per pod” decisions are tied to measured tick and coordination cost, not only static guardrail numbers.

Every measured envelope must identify the deployment profile, hardware and resource allocation, FireMUD software version or artifact, workload fixture, and measurement date. Environment docs and load-test reports should record any deviations from these starting numbers along with the observed tick and unreplicated-write exposure metrics so operators can make informed scaling decisions in future iterations.
