# FireMUD System Architecture: Backup & Disaster Recovery

This document defines FireMUD’s canonical backup model, restore-mode selection, and environment recovery workflow.

Backup expectations are defined by environment class:

- **production**: scheduled backups and verification are mandatory.
- **hobby-self-hosted**: backups are mandatory with a minimum baseline of at least daily logical backup, at least 7 daily restore points retained, and at least one restore drill every 30 days.
- **staging**: disposable by default with no scheduled backups unless explicitly enabled for specific goals.
- **local-dev / pr-preview / dev-demo-cluster**: ad hoc or no-backup posture unless explicitly upgraded. `pr-preview` persists mutable state only for the lifetime of the PR and loses that state if the preview node or its storage is lost.

Staging is disposable by default, but if it is restored from production-origin data it must still follow the same restore quarantine, post-restore hardening, external credential validation, and smoke-validation flow before it is considered player-facing again.

## Implementation Notes

The main body of this document describes the target-state backup workflow. Current implementation may lag the target state in a few areas:

- `PauseTicksForScope` / `ResumeTicksForScope` support pausing by `tenant_id` + `game_instance_id` today; `region_id` scoping exists in the proto contract but is not yet enforced end to end.
- Backup-related spans and metrics should still use the target-state names and units documented here so dashboards and alert rules remain stable as scope support expands.
- Player-facing prod-like environments are not coordinated-backup-ready until automated backups invoke pause/resume with canonical `tenant_id + region_id` scope end to end.
- Until that convergence is complete, player-facing production releases that would depend on restore-point recovery rather than binary rollback are non-compliant for promotion.
- Alias-scope migration notes in this doc are temporary bridge guidance and should be removed once canonical region scope is enforced end to end.

Canonical current-state note:

- Player-facing target state is canonical `tenant_id + region_id` scope.
- Alias-scoped `game_instance_id` maintenance remains a temporary bridge for non-player-facing drills, quarantined rehearsals, and explicitly recorded manual maintenance only.
- When region-scoped pause/resume is enforced end to end, the migration-plan sections in this doc and related runbooks should be removed rather than preserved as standing operator guidance.

## Documentation Map

- [`system-architecture-backup-recovery-evidence-and-compliance.md`](./system-architecture-backup-recovery-evidence-and-compliance.md)
  - restore-proof records, readiness evidence, traffic-open evidence, backup metrics, and environment-specific compliance artifacts
- [`system-architecture-post-restore-hardening.md`](./system-architecture-post-restore-hardening.md)
  - restore quarantine, JWT/JWKS rotation, DB credential rotation, certificate reissuance, external credential validation, and reopen gates

## PostgreSQL Logical Backups

- `firemud-pg-dump` runs every 15 minutes and stores compressed SQL dumps.
- The CronJob authenticates to the Game Session control plane to invoke `PauseTicks` / `ResumeTicks` through narrowly scoped service-account and mTLS identity bindings.
- Production Terraform deploys this CronJob automatically.
- Retention policy:
  - 24 hours of 15-minute dumps
  - 10 days of daily dumps
  - 3 weekly dumps
  - 3 monthly dumps
- Dumps are written to `firemud-pg-dumps` and may also upload to object storage when `PG_DUMP_BUCKET` is configured.
- In production, skipped object-storage uploads are a misconfiguration even if short-term dumps remain on PVC.
- Velero schedules back up Kubernetes manifests only, with `snapshotVolumes: false`.

### Coordinated Tick Pausing

PostgreSQL dumps must capture a consistent view of gameplay state. Before `pg_dump` begins, Game Session exposes `PauseTicks` and `ResumeTicks`. The backup workflow:

1. Calls `PauseTicks` with a reason string.
2. Polls `GetRegionTickStatus` until every affected region reports canonical `PAUSED`, meaning `commandIntakeBlocked=true`, `batchAllocationBlocked=true`, and no executor in scope can create new coordination state under the pre-pause epoch.
3. Starts `pg_dump` immediately once pause is confirmed.
4. Invokes `ResumeTicks` so command intake and tick scheduling resume.

Operational constraints:

- Tick pausing must be bounded and observable.
- Backup and reset tooling must use the same pause/status contract.
- Pause scope should be limited to the smallest safe blast radius.
- If a pause does not reach `PAUSED` within budget, the backup job must fail fast and alert operators instead of producing an inconsistent dump.
- Recommended pause-wait budget is `max(10s, 2 * tick_interval_ms)` for the affected scope.
- For player-facing scopes, “pause wait exceeded” and “scope still paused” are `P0`; freshness and verification failures remain `P1`.

Canonical backup/recovery severity matrix:

| Condition family | Canonical severity | Notes |
| --- | --- | --- |
| `backup_tick_pause_wait_budget_breached`, `backup_tick_pause_duration_budget_breached`, `backup_ticks_paused_budget_breached` on player-facing scopes | `P0` | Active player-facing safety breach during coordinated backup or recovery gating |
| `backup_pipeline_recent_backup_slo_breached` | `P1` | Fresh backup signal missing for required environment class |
| `backup_pipeline_recent_verification_slo_breached` | `P1` by default (`P2` only where environment policy explicitly downgrades) | Verification freshness degraded |
| `backup_pipeline_recent_restore_drill_slo_breached` | `P1` | Restore-proof freshness degraded for reopen/promotion decisions |

For convenience, `dev-tools/backups/firemud-backup.sh` automates the pause, wait, dump, and resume sequence.

### Tick Pause Scope Contract

Tick pause/resume APIs support multiple ways to identify scope, but the long-term canonical scope is `tenant_id + region_id`.

- The canonical scope is `tenant_id + region_id`.
- `game_instance_id` is an alias scope allowed only when a game instance maps cleanly to a single tick region.
- Requests must set `tenant_id` and exactly one of `region_id` or `game_instance_id`.
- If both or neither are set, the request is rejected as `INVALID_ARGUMENT`.
- Automated coordinated backups for player-facing prod-like environments must already use canonical region scope.
- Alias-scoped `game_instance_id` pause/resume is allowed only for non-player-facing restore drills, quarantined staging rehearsals, and manual operator workflows that record explicit scope-resolution evidence.
- Manual alias-scoped maintenance operations must write an audit record showing the requested alias scope, the resolved `tenant_id`, the resolved `region_id` set, actor identity, and start/end timestamps.

Evidence schema versions referenced by this workflow:

- `backup-maintenance-record/v1` for alias-scope maintenance and pause-scope audit evidence
- `recovery-record/v1` for canonical player-facing restore evidence
- `traffic-open-record/v1` for `hobby-self-hosted` first-live and reopen evidence

### Tick Pause Scope Migration Plan

The control-plane migration should follow explicit phases:

1. **Phase A**: accept both `region_id` and `game_instance_id`, emit alias-usage metrics, and log deprecation warnings for alias scope.
2. **Phase B**: keep dual acceptance but require dashboards and alerts for alias-scope usage.
3. **Phase C**: reject `game_instance_id`-only pause/resume requests with `INVALID_ARGUMENT`.

Exit criteria for Phase C:

- all prod-like backup jobs use `tenant_id + region_id`
- incident tooling and runbooks no longer rely on alias fallback
- alias-scope usage is zero for a full release window

## Redis Persistence

- Redis stores only transient state:
  - Coordination Redis stores volatile coordination state and uses AOF for crash recovery while the cluster is running.
  - Cache/Rate-Limit Redis stores best-effort caches and rate-limit counters and is not treated as durable.
- Redis is not restored from backup during a cold start.
- If Coordination Redis starts empty, treat it as a coordination reset event and follow the Coordination Reset Model rather than trying to restore Redis from backup images.
- After a PostgreSQL rewind, coordination state is rebuilt from PostgreSQL state and new activity where possible rather than restored from Redis backup images. Reset-sensitive prefixes such as `session:game:*` and `session:auth:*` may be dropped as part of this rebuild, so player re-login and internal token re-authentication should be expected where applicable.
- In development, persisted AOF is a debugging tool only and should be restored into isolated throwaway instances unless service and Lua-script versions are known to match.

### Redis AOF Reset on Deployment

Redis is a transient coordination layer in terms of authority, but a long-lived availability dependency in staging and production:

- In development and ephemeral test environments, a reset job may wipe Coordination Redis on install or upgrade.
- In staging and production-equivalent environments, Redis AOF files and volumes are not wiped as part of normal Helm upgrades.
- Resetting Redis in staging or production is an explicit operational maintenance action equivalent to a world restart.

This is distinct from:

- **failover**: AOF and replication state are preserved, allowing replay or completion of in-flight work
- **cold start**: Redis starts empty and must be treated as a coordination reset event
- **deployment**: application pods roll while Redis keeps its AOF and in-memory state

## Restore Mode Selection

Every restore that rewinds PostgreSQL must select one explicit Coordination Redis recovery mode before the environment may reopen:

- `cold_start_restore`
  - use when Coordination Redis is known to be empty for the restored environment
  - requires proof of empty coordination state and the same post-restore hardening, external credential validation, and smoke-verification evidence required for player-facing reopen
- `scoped_reset_restore`
  - use when Coordination Redis state may survive while PostgreSQL has been rewound
  - requires proof of the canonical reset handshake and reset-sensitive session/auth handling

Ambiguous restore behavior is not allowed:

- operators must not simply restore PostgreSQL and restart everything without explicitly classifying surviving coordination/session state
- any player-facing restore that cannot prove one of the two modes above is non-compliant and must remain quarantined

## Kubernetes Production

- Velero backs up Deployments, StatefulSets, ConfigMaps, and Secrets but not volume snapshots.
- `restore-cluster.sh` is a restore-bootstrap step only unless it explicitly documents the full coordination-recovery and post-restore-hardening flow for the target environment.
- Manual restore still requires restore-mode selection, coordination recovery, post-restore hardening, external credential validation, and smoke verification before traffic may reopen.
- `FIREMUD_K8S_NAMESPACE` remains the explicit override for throwaway restore drills and non-default restore targets.
- Manual restore guidance still includes the concrete bootstrap sequence: copy the desired dump, restore it with `psql`, restart Deployments and StatefulSets, and wait for rollout completion before proceeding to recovery-mode gating.
- If dumps live in `PG_DUMP_BUCKET`, download them first with `aws s3 cp ...`, adding `--endpoint-url` for MinIO-backed buckets as needed.

Manual bootstrap example sequence:

1. Copy the desired dump out of the PostgreSQL pod.
2. Restore it into the target PostgreSQL pod with `psql`.
3. Restart Deployments and StatefulSets in the target namespace.
4. Wait for rollout completion before marking the bootstrap step successful.
5. Treat the rollout as database/bootstrap only; recovery mode, coordination recovery, post-restore hardening, external credential validation, and smoke verification still gate traffic reopen.

## Local Development

- Restore with `dev-tools/restores/restore-db.sh` or `restore-latest-db.sh`.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services restart with Docker Compose.
- If Coordination Redis starts empty, treat it as a coordination reset event rather than expecting in-flight timers, retries, or sessions to survive.
- The compose stack includes `pg-dump-cron` running every 15 minutes to mirror the production schedule.
- For local clusters without cloud storage, operators may deploy `k8s/velero/minio.yaml` and run `dev-tools/backups/setup-local-backup.sh` to bootstrap MinIO plus Velero. `defaultVolumesToFsBackup` must remain `false`.

## Backup Verification & Restoration Testing

- `verify-backups.sh` proves backup artifacts exist, are readable, and remain compatible with supported recovery tooling.
- Restore drills prove the artifacts, restore tooling, restore-mode selection, and post-restore hardening flow actually produce a recoverable environment.
- Every successful restore drill must record:
  - selected restore mode
  - restore-tool success
  - smoke success
  - required post-restore hardening and validation results
  - immutable evidence references
- Throwaway Kubernetes drills normally use `restore-cluster.sh <backup-name> <namespace>` or `FIREMUD_K8S_NAMESPACE` to target a non-default namespace.

Backup observability, restore-proof artifacts, and traffic-open evidence are defined in [`system-architecture-backup-recovery-evidence-and-compliance.md`](./system-architecture-backup-recovery-evidence-and-compliance.md).

## Restore Workflow Summary

| Environment | Steps |
| --- | --- |
| **Kubernetes** | Restore PostgreSQL from `pg_dump` -> choose and record `cold_start_restore` or `scoped_reset_restore` -> restore manifests -> run the coordination recovery gate -> run post-restore hardening and smoke checks -> reopen traffic only after recovery evidence is complete |
| **Docker Compose** | Restore DB snapshot -> choose and record `cold_start_restore` or `scoped_reset_restore` -> restart containers only after the chosen coordination recovery path is understood -> follow cold-start/reset behavior and local validation before reopening traffic |

Redis always uses AOF for crash recovery during runtime but is never restored from backup images. If Coordination Redis starts empty, treat it as a reset/cold-start scenario as described in the Redis architecture docs.

For diagnostic purposes, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into isolated throwaway Redis instances to inspect keys and coordination history during incident analysis. These snapshots are strictly read-only tools and must never be restored into live Coordination Redis clusters as rollback images.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Redis Reset and Recovery](./system-architecture-redis-reset-and-recovery.md)
- [Runbooks](./system-architecture-runbooks.md#recovery)
- [Deploy Preflight Policy](./system-architecture-deploy-preflight-policy.md)
- [Deployment Runbook](./system-architecture-deployment-runbook.md)
