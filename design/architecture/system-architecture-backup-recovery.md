# FireMUD System Architecture: Backup & Disaster Recovery

This document defines FireMUD’s canonical backup model, restore-mode selection, and environment recovery workflow.

Backup expectations are defined by environment class:

- **production**: scheduled backups and verification are mandatory; the initial hosted RPO objective is 15 minutes measured from the newest verified restorable point.
- **hobby-self-hosted**: the supported default configures backups and an automated local restore rehearsal. A deployment may explicitly open first-live as `recovery-unverified` with no restore-readiness promise. Retaining verified status requires at least daily logical backup, at least 7 daily restore points, and a current restore drill. No unverified option bypasses quarantine after an actual restore.
- **staging**: disposable by default with no scheduled backups unless explicitly enabled for specific goals.
- **local-dev / pr-preview / dev-demo-cluster**: ad hoc or no-backup posture unless explicitly upgraded. `pr-preview` persists mutable state only for the lifetime of the PR and loses that state if the preview node or its storage is lost.

Staging is disposable by default, but if it is restored from production-origin data it must still follow the same restore quarantine, post-restore hardening, external credential validation, and smoke-validation flow before it is considered player-facing again.

## Implementation Notes

The main body of this document describes the target-state backup workflow. Current implementation may lag the target state in a few areas:

- The scheduled Kubernetes CronJob performs an online `pg_dump`, but it does not yet record the complete environment/schema/service/tool lineage or prove that the artifact can pass the environment-wide cold-start recovery workflow.
- The configured 15-minute Cron schedule is not proof of the 15-minute hosted RPO. Completion, upload, lineage validation, restore-readability, and the age of the newest verified restorable point are not yet measured end to end.
- `PauseTicksForScope` / `ResumeTicksForScope` support pausing by `tenant_id` + `game_instance_id` today; `region_id` scoping exists in the proto contract but is not yet enforced end to end.
- The live ownership/status read is currently `GetRuntimeOwnershipStatus` at the `{tenantId, gameInstanceId}` boundary, not the fuller target-state `GetRegionTickStatus(scope)` surface used throughout the long-term maintenance and reset contract.
- Region pause/status remains incomplete for maintenance and future scoped recovery, but routine online backups do not depend on tick pause.
- Player-facing restore-point recovery remains unsupported because enforced restore quarantine, empty-Redis proof, environment-wide durable convergence, session/epoch invalidation, post-restore hardening automation, and complete recovery-record validation are not implemented end to end.
- Scheduled isolated drills, resumable recovery orchestration, operator-authorized recovery-point selection, and crash-recoverable controlled reopen are not implemented. Existing scripts remain bootstrap helpers rather than the unattended playbooks required for single-operator production.
- Until that convergence is complete, production first-live, reopen after PostgreSQL rewind, and `roll-forward-only` production promotion are non-compliant.

Canonical current-state note:

- Player-facing database restore is environment-wide because the backup rewinds the shared PostgreSQL database for every tenant and service schema.
- The initially supported recovery mode is `cold_start_restore` with empty Coordination Redis, full session invalidation, and environment-wide epoch/fence reset.
- Alias-scoped `game_instance_id` maintenance remains a temporary bridge for non-player-facing drills, quarantined rehearsals, and explicitly recorded manual maintenance only. It is not backup or restore-readiness evidence.
- Player-facing `scoped_reset_restore` is deferred until a separate design and proof package establishes canonical region ownership and surviving-Redis reconciliation.

## Documentation Map

- [`system-architecture-backup-recovery-evidence-and-compliance.md`](./system-architecture-backup-recovery-evidence-and-compliance.md)
  - restore-proof records, readiness evidence, traffic-open evidence, backup metrics, and environment-specific compliance artifacts
- [`system-architecture-post-restore-hardening.md`](./system-architecture-post-restore-hardening.md)
  - restore quarantine, JWT/JWKS rotation, DB credential rotation, certificate reissuance, external credential validation, and reopen gates

## PostgreSQL Logical Backups

- `firemud-pg-dump` runs every 15 minutes and stores compressed SQL dumps.
- The CronJob takes an online transactionally consistent PostgreSQL snapshot while normal writes continue; routine backup does not pause gameplay.
- The hosted-production RPO objective is 15 minutes measured from the newest artifact that has passed integrity, environment/database lineage, restore-readability, and supported-tooling validation. Scheduling alone does not satisfy it, and implementations may need to run more frequently to preserve verification margin.
- Each artifact records environment/database identity, snapshot time, schema and migration lineage, deployed service digests, backup-tool digest, and object-storage binding.
- Production Terraform deploys this CronJob automatically.
- Retention policy:
  - 24 hours of 15-minute dumps
  - 10 days of daily dumps
  - 3 weekly dumps
  - 3 monthly dumps
- Dumps are written to `firemud-pg-dumps` and may also upload to object storage when `PG_DUMP_BUCKET` is configured.
- In production, skipped object-storage uploads are a misconfiguration even if short-term dumps remain on PVC.
- When hosted production has no verified restorable point within the objective, raise the configured backup incident and block production promotion until evidence is current. Do not automatically stop otherwise healthy gameplay merely because backup freshness has degraded.
- Velero schedules back up Kubernetes manifests only, with `snapshotVolumes: false`.
- Backups are immutable until normal expiry and may contain subject data erased after their snapshot time. Under [ADR 0050](./decisions/adr-0050-versioned-export-retention-and-erasure-policy.md), restore quarantine must replay durable erasure and tombstone state through the restored boundary before traffic reopens so deleted identity and authority are not resurrected.

### Online Snapshot Contract

The backup artifact is one consistent PostgreSQL database view, not a tenant- or region-scoped gameplay artifact. Online backup correctness requires:

- one transactionally consistent snapshot covering every service schema in the declared database;
- immutable environment, database, schema/migration, service-digest, tool-digest, snapshot-time, and object-store lineage;
- artifact integrity and a restore-readability check rather than object-existence proof alone;
- no claim that the snapshot also preserves Coordination Redis, active sessions, queued transient work, or external provider state; and
- periodic production-equivalent proof that durable workflow and external-effect reconciliation can recover from an artifact captured while representative writes are active.

Cross-service workflows may be captured between durable steps, as they may be during an abrupt crash. The player-facing readiness boundary is therefore restore-time convergence, not recurring write quiescence. Every declared and enabled durable participant must be idempotently replayable, externally reconcilable, deterministically terminalizable or invalidatable, durably fenceable/disableable with retained backlog, or an explicit blocker to reopen.

Backup execution, artifact validation, freshness calculation, isolated restore testing, and evidence generation are automated. Routine proof cannot depend on an operator manually entering timestamps or reconstructing a restore procedure during an incident.

Canonical backup/recovery severity matrix:

| Condition family | Canonical severity | Notes |
| --- | --- | --- |
| `backup_pipeline_recent_backup_slo_breached` | `P1` | Fresh backup signal missing for required environment class |
| `backup_pipeline_recent_verification_slo_breached` | `P1` by default (`P2` only where environment policy explicitly downgrades) | Verification freshness degraded |
| `backup_pipeline_recent_restore_drill_slo_breached` | `P1` | Restore-proof freshness degraded for reopen/promotion decisions |

Pause-budget alerts remain valid for maintenance/reset workflows but are not routine backup signals.

### Maintenance Tick Pause Scope Contract

Tick pause/resume APIs support multiple ways to identify scope for maintenance, reset, migration, and future scoped recovery, but the long-term canonical scope is `tenant_id + region_id`.

- The canonical scope is `tenant_id + region_id`.
- `game_instance_id` is an alias scope allowed only when a game instance maps cleanly to a single tick region.
- Requests must set `tenant_id` and exactly one of `region_id` or `game_instance_id`.
- If both or neither are set, the request is rejected as `INVALID_ARGUMENT`.
- Routine online backups do not invoke this contract and do not use pause scope as recovery evidence.
- Alias-scoped `game_instance_id` pause/resume is allowed only for non-player-facing maintenance drills, quarantined staging rehearsals, and manual operator workflows that record explicit scope-resolution evidence.
- Manual alias-scoped maintenance operations must write an audit record showing the requested alias scope, the resolved `tenant_id`, the resolved `region_id` set, actor identity, and start/end timestamps.

Evidence schema versions referenced by this workflow:

- `backup-maintenance-record/v1` remains the historical schema name for alias-scope maintenance and pause-scope audit evidence; new evidence must classify the operation as maintenance rather than routine backup
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

Every player-facing restore that rewinds PostgreSQL uses environment-wide `cold_start_restore`:

- Coordination Redis must be empty for the restored environment before recovery participants run.
- Recovery advances or recreates every gameplay epoch/fence, invalidates gameplay and Account sessions, obtains a safe disposition for every declared and enabled durable workflow and external-effect family, and rebuilds coordination state only from restored durable authority plus new post-restore activity.
- Proof of empty coordination state, complete participant disposition, post-restore hardening, external credential validation, secret-compliance refresh, and smoke verification is required before reopen.

Ambiguous or mixed-timeline restore behavior is not allowed:

- operators must not restore PostgreSQL and restart everything without enforced quarantine and cold-start classification;
- surviving Redis must not be retained or merged with the older database;
- any player-facing restore that cannot prove environment-wide `cold_start_restore` remains quarantined; and
- tenant-local or region-local rewind from the whole-database artifact is unsupported.

`scoped_reset_restore` is a deferred future mode for quarantined experiments only. It requires a separate accepted design and complete region ownership, scope inventory, stale-state rejection, session policy, and reconciliation proof before it can become player-facing.

Routine service, pod, node, and environment restarts that do not rewind PostgreSQL remain automatic availability operations and do not enter this destructive restore workflow. For an actual rewind, automation establishes quarantine and fencing, verifies the candidate, restores and reconciles the environment, runs hardening and smoke checks, and prepares controlled reopen through idempotent durable steps.

Automation needed to recover from complete cluster loss must have an invocation location outside that cluster when a deployment claims unattended repair, but it does not require a second Kubernetes control plane. An external allowlisted runner may rebuild or restart the same attested boundary and run checks in quarantine; it may not select a recovery point, activate a competing authority, or reopen traffic unless the separately authorized recovery state permits it.

The destructive recovery-point choice and displayed data-loss window require operator authorization by default. A future explicitly configured automatic-DR policy may pre-authorize a maximum loss window only with strict old-authority fencing and candidate-selection proof; this baseline does not enable it.

### Logical Backup Scale and PITR Trigger

Online logical backups remain the initial hosted, hobby, and small-deployment baseline. Hobby/self-hosted operators may select a slower cadence, but operator status and recovery evidence must show the configured policy, effective RPO, and age of the newest verified restorable point.

Hosted production adopts PostgreSQL physical backup with WAL archiving and point-in-time recovery when logical backups cannot reliably maintain the measured 15-minute objective or when dump duration, overlap, storage behavior, or runtime load materially harms the live platform. PITR changes point selection, not the environment-wide quarantine, empty-Redis reset, durable convergence, external reconciliation, hardening, or controlled-reopen boundary.

## Kubernetes Production

- Velero backs up Deployments, StatefulSets, ConfigMaps, and Secrets but not volume snapshots.
- `restore-cluster.sh` is a restore-bootstrap step only unless it explicitly documents the full restore-safe-mode, coordination-recovery, and post-restore-hardening flow for the target environment.
- Manual restore still requires restore-safe mode, environment-wide cold-start recovery, post-restore hardening, external credential validation, and smoke verification before traffic may reopen.
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
5. Prove empty Coordination Redis and record environment-wide `cold_start_restore`.
6. Complete offline participant convergence, epoch/fence reset, session invalidation, and coordination initialization before any normal Game Session or automation worker can create fresh coordination state.
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

- `verify-backups.sh` proves backup artifacts exist, are readable, and remain compatible with supported recovery tooling.
- Restore drills prove the artifacts, restore tooling, restore-mode selection, and post-restore hardening flow actually produce a recoverable environment.
- Every successful restore drill must record:
  - environment-wide `cold_start_restore` and empty-Redis proof
  - restore-tool success
  - complete safe-disposition results for every declared and enabled durable participant and external-effect family
  - smoke success
  - required post-restore hardening and validation results
  - immutable evidence references
- Throwaway Kubernetes drills normally use `restore-cluster.sh <backup-name> <namespace>` or `FIREMUD_K8S_NAMESPACE` to target a non-default namespace.

Run a full production-equivalent drill at least every 30 days. Ordinary rollback-compatible production releases reuse that baseline through the compact recovery-compatibility result when restore semantics and contracts remain compatible. Changes that alter restore compatibility or recovery semantics require a new drill; `roll-forward-only` releases always require an exact release-candidate drill from current production database lineage, and first-live/reopen events require evidence for the boundary being opened.

Backup observability, restore-proof artifacts, and traffic-open evidence are defined in [`system-architecture-backup-recovery-evidence-and-compliance.md`](./system-architecture-backup-recovery-evidence-and-compliance.md).

## Restore Workflow Summary

| Environment | Steps |
| --- | --- |
| **Kubernetes** | Enter restore-safe quarantine -> restore PostgreSQL from the online snapshot artifact -> prove empty Coordination Redis -> run environment-wide offline convergence, epoch/fence reset, and session invalidation -> restore manifests with normal workloads still closed -> run post-restore hardening and smoke checks -> reopen traffic only after recovery evidence is complete |
| **Docker Compose** | Restore DB snapshot -> clear Coordination Redis -> run the environment-wide cold-start convergence path -> restart containers only after local validation and session invalidation are understood |

Redis always uses AOF for crash recovery during runtime but is never restored from backup images. If Coordination Redis starts empty, treat it as a reset/cold-start scenario as described in the Redis architecture docs.

For diagnostic purposes, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into isolated throwaway Redis instances to inspect keys and coordination history during incident analysis. These snapshots are strictly read-only tools and must never be restored into live Coordination Redis clusters as rollback images.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Redis Reset and Recovery](./system-architecture-redis-reset-and-recovery.md)
- [Runbooks](./system-architecture-runbooks.md#recovery)
- [Deploy Preflight Policy](./system-architecture-deploy-preflight-policy.md)
- [Deployment Runbook](./system-architecture-deployment-runbook.md)
