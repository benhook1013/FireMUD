# FireMUD Operational Runbooks

This document serves as an **index** to operational runbooks for FireMUD. It provides a high-level map of operator workflows and links to detailed, per-topic runbooks.

---

## Deployment

High-level steps and operator responsibilities for rolling out new versions of FireMUD to Kubernetes-backed environments.

See: `design/architecture/system-architecture-deployment-runbook.md`.

## Scaling

Guidance on scaling services and infrastructure (Gateway, Game Session, Redis, PostgreSQL) in response to increased load.

See: `design/architecture/system-architecture-scaling-runbook.md`.

## Operator Access

Operator workflows for mTLS client certificates and other control-plane credentials.

See: `design/architecture/system-architecture-operator-credentials-runbook.md`.

## Recovery

This section covers recovery scenarios at a high level; detailed, per-topic runbooks live in their own files.

### Database Failure and Cluster Restore

- For PostgreSQL backup and restore procedures (including `dev-tools/restores/restore-cluster.sh` and manual `kubectl cp`/`psql` flows), see:
  - `design/architecture/system-architecture-backup-recovery.md`

### Redis Session Schema and TTL Cleanup

- For the scoped session cleanup workflow (metrics, tenant-scoped prefixes such as `session:game:{tenantGameplayTag}:*`, and large-keyspace safety guidelines), see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#session-schema-and-ttl-cleanup`

### Redis Incident Scenarios

- For Coordination/Cache Redis outages, AOF problems, tail-loss SLO breaches, mis-sharded keys, and automation queue schema issues, see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#redis-incident-scenarios`

### Tick Incident Scenarios

- For stalled regions, replay storms, durable commit/coordination cleanup divergence, or stuck tick effect ledger entries, see:
  - `design/architecture/system-architecture-tick-incident-runbook.md`

### Player Experience Incidents

- For login success ratio drops, elevated command latency, chat delivery latency, or Telnet/WebSocket path availability issues, see:
  - `design/architecture/system-architecture-player-experience-incident-runbook.md`

### Observability Stack Incidents

- For incidents where Prometheus, Alertmanager, Elasticsearch/Kibana, Grafana, or Jaeger/collector are degraded or unavailable, see:
  - `design/architecture/system-architecture-observability-incident-runbook.md`

### Minimal Coordination & Tick Operator Mental Model

For a single-admin operator, most “what do I do now?” coordination/tick questions reduce to three named operations and a small set of dashboards/metrics:

- **Dashboards / metrics to watch**
- Tick health:
  - `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` vs `tick_lock_ttl_ms`.
  - Per-region tick status (RUNNING/PAUSED/STALLED) and last-committed `tickId`.
  - Queue depths and retry counts (for example `tick_retry_queue_depth`).
- Redis tail-loss and memory:
  - Tail-loss SLO metrics as described in `system-architecture-redis-operations.md` (for example `redis_coordination_tail_loss_ms{scope}`).
  - Coordination Redis memory and key counts (coordination prefixes vs total).
- Tick effect ledger:
  - `tick_effects_pending_total`, `tick_effects_applied_total`, `tick_effects_abandoned_total{reason}`.
  - `tick_durable_commit_total`, `tick_coordination_cleared_total`, `tick_cleanup_lag_ms` to detect durable/cleanup divergence.
  - `current_tick_state`, `current_tick_terminal_at_ms`, `tick_effects_replay_slo_breached`, and `tick_effects_replay_starved` to detect replay and cleanup pressure.
- Cluster health:
  - Redis primary/replica health and coordination-topology alerts.

- **Named operations**
  - Tail-loss incidents first choose a **replay-first** or **reset-first** recovery mode. Scope selection for resets happens only after `reset-first` is chosen.
  - **Per-region reset** – for a single `<tenantId, gameInstanceId, regionId>`, atomically acquire the maintenance fence and bump the authoritative `region_epoch` before clearing, rebuilding, rebinding, or reopening anything. Surviving executors, leases, locks, sessions, and observers from the prior epoch must be rejected as stale by that fence. Then clear only the documented region-scoped coordination keys: `tick:{tenantRegionTag}:meta`, `tick:{tenantRegionTag}:pending`, `tick:{tenantRegionTag}:queue:<entityId>`, `tick:{tenantRegionTag}:lock:<entityId>`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, `tick-executor-lease:{tenantRegionTag}`, `automation:timer:{tenantRegionTag}`, and `script-scheduler:{tenantRegionTag}:lastTickId`. For session state, clear only the affected `tick:{tenantRegionTag}:session-binding:<entityId>` session-to-region bridge keys; do not use a region-only scan to delete the broader `session:game:*`, `sessionctx:*`, or `session:auth:*` families. The reset-sensitive `session:auth:*` state still requires auth/revocation validation and any required re-authentication for preserved sessions before rebind. Also drop the reset-tolerant observer hints `tick-events-lease:{tenantRegionTag}`, `tick-events:{tenantRegionTag}`, and `tick-events-offset:{tenantRegionTag}`. Automation rebuilds timer indexes and scheduler checkpoints from durable schedules and the active status/progress adapter; observers reacquire leases and re-establish baselines from the same active adapter and durable domain state, so loss or duplication affects discovery latency but not correctness. Rebind preserved sessions through the bridge, then require `RunPostResetSmokeCheck(scope)` to pass before reopening command intake, player traffic, or tick scheduling/rebuild; only after that gate may ticks rebuild from PostgreSQL and the tick effect ledger.
  - **Per-tenant reset** – clear coordination state for all regions for a single tenant and treat it as a tenant-scoped maintenance/reset event. Tenant resets always invalidate `session:auth:*`; preserve `session:game:*` / current `sessionctx:*` context only when the operator explicitly chooses that option for the reset, and rebind preserved sessions, run `RunPostResetSmokeCheck(scope)`, and pass that gate before command intake, traffic, or tick scheduling resumes.
  - **Cluster reset** – clear coordination state for all tenants/regions on a Coordination Redis deployment; reserved for catastrophic incidents or planned migrations.

- **How to choose**
  - If metrics show a brief blip but tail-loss and tick health have already recovered and invariants are intact → **Accept loss and monitor** (no active operation).
  - If tail-loss is sustained but the region is still on one coherent timeline and replay can make bounded progress → choose **replay-first**.
  - If the region is `STALLED`, mixed-epoch/orphaned state is suspected, or replay-first cannot make bounded progress → choose **reset-first**, then pick the smallest safe reset scope below.
  - If a problem is clearly confined to one region (for example, mis-keyed `tick:*` data or a stuck `pending` entry) → run a **per-region reset**.
  - If multiple regions for the same tenant are polluted or broken in similar ways → run a **per-tenant reset**.
  - Only when corruption or topology changes are broad and cannot be addressed region/tenant by tenant (for example, AOF directory corruption, cluster-wide hash-tag mistakes) should you plan a **cluster reset**, ideally during a maintenance window.

Detailed step-by-step commands for these operations live in:

- `design/architecture/system-architecture-redis-incident-runbook.md#coordination-aof-tail-loss-slo-breach`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`
- `design/architecture/system-architecture-redis-operations.md`

## Asset Store

Operational guidance for the asset store used by the Game Design Service, including health checks and incident handling.

See: `design/architecture/system-architecture-asset-store-runbook.md`.

## Hotfix Procedure

- Use standard deployment workflows with a minimal, well-scoped change set.
- Ensure tests and smoke checks are run for the hotfix branch.
- Coordinate any emergency schema or configuration changes with the relevant architecture owners.

## Telnet Path Degraded or Failing

Operator guidance for incidents affecting the Telnet path through the TCP Proxy Service, including interpretation of TCP Proxy buffer/slow-client metrics and comparisons against the WebSocket path when only Telnet is impacted.

See: `design/architecture/system-architecture-telnet-degraded-runbook.md` (which also cross-references the ordering/backpressure contracts in `system-architecture-protocol-bridging.md`).

## Related Documentation

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/system-architecture-cicd.md`
