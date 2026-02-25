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

- For the scoped session cleanup Job (metrics, per-tenant prefixes such as `session:game:<tenantId>:*`, and large-keyspace safety guidelines), see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#session-schema-and-ttl-cleanup`

### Redis Incident Scenarios

- For Coordination/Cache Redis outages, AOF problems, tail-loss SLO breaches, mis-sharded keys, and automation queue schema issues, see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#redis-incident-scenarios`

### Tick Incident Scenarios

- For stalled regions, replay storms, durable commit/coordination cleanup divergence, or stuck tick effect ledger entries, see:
  - `design/architecture/system-architecture-tick-incident-runbook.md`

### Player Experience Incidents

- For login success ratio drops, elevated command latency, or chat delivery latency issues, see:
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
  - Tail-loss SLO metrics as described in `system-architecture-redis-operations.md` (for example `redis_coordination_tail_loss_ms{tenantId,regionId}`).
  - Coordination Redis memory and key counts (coordination prefixes vs total).
- Tick effect ledger:
  - `tick_effects_pending_total`, `tick_effects_applied_total`, `tick_effects_abandoned_total{reason}`.
  - `tick_durable_commit_total`, `tick_coordination_cleared_total`, `tick_cleanup_lag_ms` to detect durable/cleanup divergence.
- Cluster health:
  - Redis primary/replica health, split-brain/sentinel alerts.

- **Named operations**
  - **Per-region reset** – clear coordination state (`tick:*`, timers, retries, leases) for a single `<tenantId, regionId>` and allow ticks to rebuild from PostgreSQL and the tick effect ledger.
  - **Per-tenant reset** – clear coordination state (all regions and sessions) for a single tenant and treat it as a tenant-scoped maintenance/reset event.
  - **Cluster reset** – clear coordination state for all tenants/regions on a Coordination Redis deployment; reserved for catastrophic incidents or planned migrations.

- **How to choose**
  - If metrics show a brief blip but tail-loss and tick health have already recovered and invariants are intact → **Accept loss and monitor** (no active operation).
  - If a problem is clearly confined to one region (for example, mis-keyed `tick:*` data or a stuck `pending` entry) → run a **per-region reset**.
  - If multiple regions for the same tenant are polluted or broken in similar ways → run a **per-tenant reset**.
  - Only when corruption or topology changes are broad and cannot be addressed region/tenant by tenant (for example, AOF directory corruption, cluster-wide hash-tag mistakes) should you plan a **cluster reset**, ideally during a maintenance window.

Detailed step-by-step commands for these operations live in:

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
