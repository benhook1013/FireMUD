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

- The scheduled Kubernetes CronJob performs an online `pg_dump`, but it does not yet record complete environment/schema/service/tool lineage or prove that the artifact can pass the environment-wide cold-start recovery workflow.
- `PauseTicksForScope` / `ResumeTicksForScope` and `GetRuntimeOwnershipStatus` remain incomplete maintenance/reset controls. Routine online backups do not depend on tick pause.
- `verify-backups.sh` currently proves only Velero backup existence and optional pg-dump object-store reachability; it does not prove immutable lineage, artifact readability, restore-tool compatibility, or readiness.
- Player-facing restore-point recovery remains unsupported because enforced restore quarantine, empty-Redis proof, environment-wide durable convergence, session/epoch invalidation, post-restore hardening automation, and complete recovery-record validation are not implemented end to end.
- Until that convergence is complete, production first-live, reopen after PostgreSQL rewind, and `roll-forward-only` production promotion are non-compliant.

Canonical current-state note:

- Player-facing database restore is environment-wide because the backup rewinds the shared PostgreSQL database for every tenant and service schema.
- The initially supported recovery mode is `cold_start_restore` with empty Coordination Redis, full session invalidation, and environment-wide epoch/fence reset.
- Alias-scoped `game_instance_id` pause/resume remains a temporary maintenance bridge and is not backup or restore-readiness evidence.

## Documentation Map

- [`system-architecture-backup-recovery-evidence-and-compliance.md`](./system-architecture-backup-recovery-evidence-and-compliance.md)
  - restore-proof records, readiness evidence, traffic-open evidence, backup metrics, and environment-specific compliance artifacts
- [`system-architecture-post-restore-hardening.md`](./system-architecture-post-restore-hardening.md)
  - restore quarantine, JWT/JWKS rotation, DB credential rotation, certificate reissuance, external credential validation, and reopen gates

## PostgreSQL Logical Backups

- `firemud-pg-dump` runs every 15 minutes and stores compressed SQL dumps.
- The CronJob takes an online transactionally consistent PostgreSQL snapshot while normal writes continue; routine backup does not pause gameplay.
- Production Terraform deploys this CronJob automatically.
- Retention policy:
  - 24 hours of 15-minute dumps
  - 10 days of daily dumps
  - 3 weekly dumps
  - 3 monthly dumps
- Dumps are written to `firemud-pg-dumps` and may also upload to object storage when `PG_DUMP_BUCKET` is configured.
- In production, skipped object-storage uploads are a misconfiguration even if short-term dumps remain on PVC.
- Velero schedules back up Kubernetes manifests only, with `snapshotVolumes: false`.

### Online Snapshot Contract

The backup artifact is one consistent PostgreSQL database view, not a tenant- or region-scoped gameplay artifact. Online backup correctness requires:

- one transactionally consistent snapshot covering every service schema in the declared database;
- immutable environment, database, schema/migration, service-digest, tool-digest, snapshot-time, and object-store lineage;
- artifact integrity and a restore-readability check rather than object-existence proof alone;
- no claim that the snapshot also preserves Coordination Redis, active sessions, queued transient work, or external provider state; and
- periodic production-equivalent proof that durable workflow and external-effect reconciliation can recover from an artifact captured while representative writes are active.

Cross-service workflows may be captured between durable steps, as they may be during an abrupt crash. The player-facing readiness boundary is therefore restore-time convergence, not recurring write quiescence. Every declared and enabled durable participant must have a deterministic safe disposition or block reopen.

Canonical backup/recovery severity matrix:

| Condition family | Canonical severity | Notes |
| --- | --- | --- |
| `backup_pipeline_recent_backup_slo_breached` | `P1` | Fresh backup signal missing for required environment class |
| `backup_pipeline_recent_verification_slo_breached` | `P1` by default (`P2` only where environment policy explicitly downgrades) | Verification freshness degraded |
| `backup_pipeline_recent_restore_drill_slo_breached` | `P1` | Restore-proof freshness degraded for reopen/promotion decisions |

Pause-budget alerts remain valid for maintenance/reset workflows but are not routine backup signals. `dev-tools/backups/firemud-backup.sh` takes an online PostgreSQL snapshot without pausing gameplay ticks.

### Maintenance Tick Pause Scope Contract

Tick pause/resume APIs support multiple ways to identify scope, but the long-term canonical scope is `tenant_id + region_id`.

- The canonical scope is `tenant_id + region_id`.
- `game_instance_id` is an alias scope allowed only when a game instance maps cleanly to a single tick region.
- Requests must set `tenant_id` and exactly one of `region_id` or `game_instance_id`.
- If both or neither are set, the request is rejected as `INVALID_ARGUMENT`.
- Routine online backups do not invoke this contract and do not use pause scope as recovery evidence.
- Alias-scoped `game_instance_id` pause/resume is allowed only for non-player-facing maintenance drills, quarantined staging rehearsals, and manual operator workflows that record explicit scope-resolution evidence.
- Manual alias-scoped maintenance operations must write an audit record showing the requested alias scope, the resolved `tenant_id`, the resolved `region_id` set, actor identity, and start/end timestamps.

Evidence schema versions referenced by this workflow:

- `backup-maintenance-record/v1` for alias-scope maintenance and pause-scope audit evidence
- `recovery-record/v1` for canonical player-facing restore evidence
- `traffic-open-record/v1` for `hobby-self-hosted` first-live and reopen evidence

### Maintenance Pause Scope Migration Plan

The control-plane migration should follow explicit phases:

1. **Phase A**: accept both `region_id` and `game_instance_id`, emit alias-usage metrics, and log deprecation warnings for alias scope.
2. **Phase B**: keep dual acceptance but require dashboards and alerts for alias-scope usage.
3. **Phase C**: reject `game_instance_id`-only pause/resume requests with `INVALID_ARGUMENT`.

Exit criteria for Phase C:

- all prod-like maintenance, reset, migration, and future scoped-recovery tools use `tenant_id + region_id`
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
  - requires proof of the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) and reset-sensitive session/auth handling

Ambiguous restore behavior is not allowed:

- operators must not simply restore PostgreSQL and restart everything without explicitly classifying surviving coordination/session state
- any player-facing restore that cannot prove one of the two modes above is non-compliant and must remain quarantined
- player-facing prod-like restores must not use pause/resume scope as recovery proof; environment-wide cold-start recovery requires its own quarantine, convergence, and controlled-reopen evidence

## Kubernetes Production

- Velero backs up Deployments, StatefulSets, ConfigMaps, and Secrets but not volume snapshots.
- `restore-cluster.sh` is a restore-bootstrap step only unless it explicitly documents the full restore-safe-mode, coordination-recovery, and post-restore-hardening flow for the target environment.
- Manual restore still requires restore-safe mode, restore-mode selection, coordination recovery, post-restore hardening, external credential validation, and smoke verification before traffic may reopen.
- A restore into a new cluster, namespace boundary, control-plane boundary, or replacement host must first run the fresh-boundary restore bootstrap defined in `system-architecture-deployment-runbook.md`. Restored snapshot-era Secrets are not authoritative trust material for the new boundary; they must be replaced, rotated, reissued, or explicitly re-bound before traffic reopen.
- Post-restore hardening must refresh the environment secret-compliance record and immutable evidence payload before quarantine is lifted, so later promotion and DR-readiness checks do not rely on pre-restore credential evidence.
- `FIREMUD_K8S_NAMESPACE` remains the explicit override for throwaway restore drills and non-default restore targets.
- Manual restore guidance still includes the concrete bootstrap sequence, but application workloads must remain stopped or restore-safe-fenced until recovery-mode gating completes. Restoring manifests is allowed; starting normal Game Session, Gateway, TCP Proxy, automation, and outbound processors before the chosen recovery mode is proven is not allowed.
- If dumps live in `PG_DUMP_BUCKET`, download them first with `aws s3 cp ...`, adding `--endpoint-url` for MinIO-backed buckets as needed.

Manual bootstrap example sequence:

1. Enter restore-safe quarantine as described in `system-architecture-post-restore-hardening.md` so player ingress, background processors, and outbound integrations cannot run with snapshot-era state.
2. Copy or download the desired dump.
3. Restore it into the target PostgreSQL pod with `psql`.
4. Restore manifests or Velero resources with normal application workloads held at zero replicas or under an enforced restore-safe startup gate; only infrastructure and maintenance Jobs required for recovery may run.
5. Choose and record exactly one restore mode: `cold_start_restore` or `scoped_reset_restore`.
6. Complete the selected coordination recovery gate before any normal Game Session or automation worker can create fresh coordination state.
7. Run post-restore hardening, external credential validation, secret-compliance evidence refresh, required sanitization checks, and smoke verification.
8. Start normal workloads and reopen traffic only after the recovery record and refreshed secret-compliance evidence are complete.

## Local Development

- Restore with `dev-tools/restores/restore-db.sh` or `restore-latest-db.sh`.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services restart with Docker Compose.
- If Coordination Redis starts empty, treat it as a coordination reset event rather than expecting in-flight timers, retries, or sessions to survive.
- The compose stack includes `pg-dump-cron` running every 15 minutes to mirror the production schedule.
- For local clusters without cloud storage, operators may deploy `k8s/velero/minio.yaml` and run `dev-tools/backups/setup-local-backup.sh` to bootstrap MinIO plus Velero. `defaultVolumesToFsBackup` must remain `false`.

## Backup Verification & Restoration Testing

- `verify-backups.sh` proves only that backup artifacts exist and optional object storage is reachable. Artifact readability, restore-tool compatibility, and player-facing readiness require separate evidence.
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
| **Kubernetes** | Enter restore-safe quarantine -> restore PostgreSQL from `pg_dump` -> restore manifests with normal workloads stopped or restore-safe-fenced -> choose and record `cold_start_restore` or `scoped_reset_restore` -> run the coordination recovery gate before normal startup -> run post-restore hardening and smoke checks -> reopen traffic only after recovery evidence is complete |
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
