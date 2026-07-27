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

- The scheduled Kubernetes CronJob performs an online `pg_dump`, but it does not yet record the complete environment/schema/service/tool lineage or prove that the artifact can pass the environment-wide cold-start recovery workflow.
- `PauseTicksForScope` / `ResumeTicksForScope` support pausing by `tenant_id` + `game_instance_id` today; `region_id` scoping exists in the proto contract but is not yet enforced end to end.
- The live ownership/status read is currently `GetRuntimeOwnershipStatus` at the `{tenantId, gameInstanceId}` boundary, not the fuller target-state `GetRegionTickStatus(scope)` surface used throughout the long-term maintenance and reset contract.
- Region pause/status remains incomplete for maintenance and future scoped recovery, but routine online backups do not depend on tick pause.
- `verify-backups.sh` currently proves only Velero backup existence and optional pg-dump object-store reachability; it does not prove immutable lineage, artifact readability, restore-tool compatibility, or readiness.
- Player-facing restore-point recovery remains unsupported because enforced restore quarantine, empty-Redis proof, environment-wide durable convergence, session/epoch invalidation, post-restore hardening automation, and complete recovery-controller/projection validation are not implemented end to end.
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
- Before opening the PostgreSQL snapshot transaction, the backup workflow reads and durably records the current committed external erasure-journal sequence as `preSnapshotJournalHighWater` solely as ordering proof. Only after that acknowledgement may it open the database snapshot. Inside the snapshot, it binds immutable `artifactErasureHighWater` to the greatest authoritative erasure-ledger sequence visible in that PostgreSQL snapshot. Each artifact records both observations with their distinct sources and meanings alongside environment/database identity, snapshot time, schema and migration lineage, deployed service digests, backup-tool digest, and object-storage binding.
- Production Terraform deploys this CronJob automatically.
- Retention policy:
  - 24 hours of 15-minute dumps
  - 10 days of daily dumps
  - 3 weekly dumps
  - 3 monthly dumps
- Dumps are written to `firemud-pg-dumps` and may also upload to object storage when `PG_DUMP_BUCKET` is configured.
- In production, skipped object-storage uploads are a misconfiguration even if short-term dumps remain on PVC.
- Velero schedules back up Kubernetes manifests only, with `snapshotVolumes: false`.
- Backups are immutable until normal expiry and may contain subject data erased after their snapshot time. Under [ADR 0050](./decisions/adr-0050-versioned-export-retention-and-erasure-policy.md), Account commits terminal erasure to an immutable, monotonic overlay journal retained outside the PostgreSQL backup lineage. Restore quarantine replays every journal sequence strictly greater than the snapshot-bound `artifactErasureHighWater` through the fixed final-cutover `restoreHighWater`, inclusive. The pre-snapshot journal observation proves ordering but is not the artifact replay boundary; a deletion committed during or after snapshot creation is covered by the fixed interval and idempotent owner reconciliation.

### Online Snapshot Contract

The backup artifact is one consistent PostgreSQL database view, not a tenant- or region-scoped gameplay artifact. Online backup correctness requires:

- one transactionally consistent snapshot covering every service schema in the declared database;
- one independently acknowledged pre-snapshot erasure-journal observation captured before the snapshot transaction opens, explicitly distinguished from the artifact replay boundary, with proof of that ordering and gap-free replay semantics;
- immutable environment, database, schema/migration, service-digest, tool-digest, snapshot-time, and object-store lineage;
- immutable `artifactErasureHighWater` captured as the greatest authoritative erasure-ledger sequence visible inside the same PostgreSQL snapshot, not the pre-snapshot journal observation, then bound with the immutable artifact digest and snapshot identity in one immutable manifest;
- one atomic or compare-and-set ready-publication record created only after the artifact bytes and manifest are durably stored; a crash, duplicate publication, missing object, mutable object, or digest/high-water mismatch leaves the candidate unpublished or quarantined and makes recovery reject it;
- artifact integrity and a restore-readability check rather than object-existence proof alone;
- no claim that the snapshot also preserves Coordination Redis, active sessions, queued transient work, or external provider state; and
- periodic production-equivalent proof that durable workflow and external-effect reconciliation can recover from an artifact captured while representative writes are active.

Cross-service workflows may be captured between durable steps, as they may be during an abrupt crash. The player-facing readiness boundary is therefore restore-time convergence, not recurring write quiescence. Every declared and enabled durable participant must be idempotently replayable, externally reconcilable, deterministically terminalizable or invalidatable, durably fenceable/disableable with retained backlog, or an explicit blocker to reopen.

## Recovery Controller Continuation

The public recovery-control verb is `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`. The one authorized invocation uses `expectedPhase=ready_to_reopen`; callers retry that same tuple and never issue a second public call for `releasing`. The controller validates the immutable evidence, atomically claims the operation from `ready_to_reopen`, and may drive the internal `releasing` work through `finalized` in that call or a retry. Each internal phase transition is durable. A crash or retryable apply/readiness failure leaves the operation in its current phase and returns a retryable attempt outcome rather than caching a terminal result. A retry from `releasing` resumes observation or the idempotent release step without applying it twice. An expected-phase or evidence mismatch fails without mutation and is not cached as the operation result. Concurrent calls with the same tuple observe the same durable attempt or final result; conflicting tuples fail closed. Exactly one terminal continuation result is recorded per `operationId`. The internal durable `pause/lock` phase holds quarantine and prevents conflicting work; recovery does not expose separate public `pause`, `resume`, `lock`, or `release-lock` verbs.

## Artifact Erasure Replay Boundary

`artifactErasureHighWater` is transactionally bound to the immutable backup snapshot and is never recalculated from restored PostgreSQL. It is the greatest authoritative erasure-ledger sequence visible in that snapshot; `preSnapshotJournalHighWater` is a separately sourced ordering observation and is not this boundary. At recovery start, the controller captures immutable `initialCatchupHighWater` once. During one bounded final cutover, the erasure authority captures immutable `restoreHighWater` once as the final readiness boundary. The controller requires `restoreHighWater >= initialCatchupHighWater >= artifactErasureHighWater`; a lower value, unknown entry, or unprovable interval keeps recovery quarantined. Before `ready_to_reopen`, recovery must replay every erasure event in the fixed interval `(artifactErasureHighWater, restoreHighWater]` into the restored environment, proving the two sub-intervals `(artifactErasureHighWater, initialCatchupHighWater]` and `(initialCatchupHighWater, restoreHighWater]` are contiguous, complete, and gap-free.

Normal erasure acceptance may continue during initial catch-up beyond immutable `initialCatchupHighWater`; those later sequences are covered by the final cutover rather than by moving the initial boundary. Readiness requires one bounded final cutover owned by the erasure authority: it fences or serializes new sequence assignment, captures immutable `restoreHighWater` once, completes the remaining fixed interval `(initialCatchupHighWater, restoreHighWater]`, and installs that fixed value as the restored environment's online erasure-consumer cursor before releasing the fence. If the cutover exceeds its configured budget before handoff, the controller keeps the fence and quarantine closed until the recorded final boundary and cursor handoff are reconciled; it never advances either boundary from a newly sampled current high-water. Missing or ambiguous capture, replay, or cursor-handoff state keeps recovery in `collecting` and retries the idempotent handoff. Erasures accepted after the proven handoff use the normal online consumer while player traffic remains closed until recovery is `finalized`.

Canonical backup/recovery severity matrix:

| Condition family | Canonical severity | Notes |
| --- | --- | --- |
| `backup_pipeline_recent_backup_slo_breached` | `P1` | Fresh backup signal missing for required environment class |
| `backup_pipeline_recent_verification_slo_breached` | `P1` by default (`P2` only where environment policy explicitly downgrades) | Verification freshness degraded |
| `backup_pipeline_recent_restore_drill_slo_breached` | `P1` | Restore-proof freshness degraded for reopen/promotion decisions |
| `backup_artifact_lineage_invalid` | `P1` | Backup artifact lineage cannot prove the expected environment, database, schema, service, tool, or object-storage binding |
| `backup_artifact_restore_unreadable` | `P1` | Backup artifact failed restore-readability validation |
| `recovery_participant_convergence_blocked` | `P1` | A recovery participant lacks a safe disposition and recovery must remain quarantined |
| Attempted player-facing reopen with incomplete cold-start convergence | `P0` | Traffic release was attempted without complete controller-authoritative recovery proof |

Pause-budget alerts remain valid for maintenance/reset workflows but are not routine backup signals.

### Maintenance Tick Pause Scope Contract

Tick pause/resume APIs support multiple ways to identify scope for maintenance, reset, migration, and future scoped recovery, but the long-term canonical region scope is `tenant_id + game_instance_id + region_id`.

- Scope class is explicit and must not be inferred from a missing field:
  - a **region operation** targets one exact `tenant_id + game_instance_id + region_id` tuple;
  - an **aggregate operation** targets an explicitly named tenant, game instance, or Coordination Redis cluster and must resolve and validate its complete affected region set before mutation;
  - an **environment-wide recovery** operation is the separate `cold_start_restore` contract and is not represented as a region pause request.
- An aggregate operation is not a region request with `region_id=null`. If one API supports both classes, it must carry an explicit operation/scope type and the resulting complete-set evidence; otherwise they use separate control-plane operations. Aggregate scope never permits silently acting on the first, partial, or stale region mapping.
- The canonical region scope is `tenant_id + game_instance_id + region_id`; region identity is never interpreted outside its owning game instance.
- Before Phase C, current aggregate requests may set `tenant_id + game_instance_id` without `region_id` only when the game instance maps cleanly to the complete affected region set. The operation must resolve that set from the authoritative mapping source, bind it to the source's mapping generation or a maintenance lease, and keep that lease held through the complete operation. Where a lease cannot be held, every mutating step must carry a compare-and-set check against the captured mapping generation and complete set. Revalidate the generation/lease and complete set immediately before execution and at each CAS-fenced step; if the generation changes, the lease expires or is lost, or the set no longer matches, fail closed without executing any further action against the stale set.
- From Phase C onward, every **region-scoped** pause/resume request must provide non-empty `tenant_id + game_instance_id + region_id`; a region request that omits any member of that complete triple is rejected with `INVALID_ARGUMENT`. Aggregate tenant/game-instance/cluster operations remain a distinct scope class and must use the explicit aggregate contract and the same complete-set generation/lease safeguards; they must not be coerced into a region request or bypass full-set validation.
- Routine online backups do not invoke this contract and do not use pause scope as recovery evidence.
- Until Phase C is complete, game-instance-scoped pause/resume is allowed only for non-player-facing maintenance drills, quarantined staging rehearsals, and manual operator workflows that record explicit scope-resolution evidence; it is never player-facing restore evidence.
- Manual game-instance-scoped maintenance operations must write an audit record showing the requested scope, the resolved `tenant_id`, `game_instance_id`, and `region_id` set, the mapping generation or maintenance-lease identity that remained held or was CAS-revalidated through execution, actor identity, and start/end timestamps. The record is valid only for the version-validated complete set that was actually executed.

Evidence schema versions referenced by this workflow:

- `backup-maintenance-record/v1` remains the historical schema name for game-instance-scoped maintenance and pause-scope audit evidence; new evidence must classify the operation as maintenance rather than routine backup
- `recovery-record/v1` for canonical player-facing restore evidence
- `traffic-open-record/v1` for `hobby-self-hosted` first-live and reopen evidence

### Maintenance Pause Scope Migration Plan

The control-plane migration should follow explicit phases:

1. **Phase A**: require `tenant_id + game_instance_id`, accept optional `region_id`, emit incomplete-scope metrics, and log warnings when region scope must be resolved.
2. **Phase B**: keep game-instance-wide acceptance but require dashboards and alerts for omitted-region usage.
3. **Phase C entry gate**: enter Phase C only after the Phase B metric records zero attempted omitted-region requests from region-scoped callers, or zero unclassified requests that should have been region-scoped, for one complete release window. Explicit aggregate operations are not counted as omitted-region drift.
4. **Phase C**: reject any **region-scoped** pause/resume request that omits `tenant_id`, `game_instance_id`, or `region_id` with `INVALID_ARGUMENT`; keep aggregate tenant/game-instance/cluster requests on their explicit aggregate contract with validated complete-set evidence.

Exit criteria for Phase C:

- all prod-like region maintenance, reset, migration, and future scoped-recovery tools use `tenant_id + game_instance_id + region_id`
- region-scoped incident tooling and runbooks no longer rely on implicit game-instance-wide region resolution; aggregate tooling names its aggregate scope and records validated complete-set evidence
- the Phase C entry gate was satisfied by zero attempted omitted-region or unclassified region requests during Phase B for one full release window; explicit aggregate operations were classified and evidenced separately
- after Phase C begins, rejected omitted-region requests remain monitored as client/tooling drift but are not treated as a migration failure or as a reason to reopen the Phase C entry gate

## Redis Persistence

- Redis stores only transient state:
  - Coordination Redis stores volatile coordination state and uses AOF for crash recovery while the cluster is running.
  - Cache/Rate-Limit Redis stores best-effort caches and rate-limit counters and is not treated as durable.
- Redis is not restored from backup during a cold start.
- If Coordination Redis starts empty, treat it as a coordination reset event and follow the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) rather than trying to restore Redis from backup images.
- After a PostgreSQL rewind, coordination state is rebuilt from PostgreSQL state and new activity where possible rather than restored from Redis backup images. Reset-sensitive prefixes such as `session:game:*` and `session:auth:*` may be dropped as part of this rebuild, so player re-login and exact-profile token re-authentication using the profile and audience classes in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#token-profiles-and-audiences) should be expected where applicable.
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

Every restore that rewinds PostgreSQL must use one explicit environment-wide `cold_start_restore` contract before the environment may reopen:

- restore PostgreSQL into an enforced quarantine boundary and replace or clear surviving Coordination Redis so the restored database is not merged with newer coordination state;
- Recovery advances or recreates every gameplay epoch/fence and must obtain Account's durable restore-cutover evidence proving invalidation of all restored Account authority and `game-session-account-delegation` lineages, plus separate Game Session recovery evidence proving invalidation of all restored gameplay bindings, before any recovered session or normal workload is admitted. Recovery only observes/reconciles those owner-specific invalidation results; it is not a second Account or Game Session invalidation writer. It then obtains a safe disposition for every declared and enabled durable workflow and external-effect family and rebuilds coordination state only from restored durable authority plus new post-restore activity.
- Recovery captures immutable `initialCatchupHighWater` and completes the gap-free interval `(artifactErasureHighWater, initialCatchupHighWater]`, then captures immutable final-cutover `restoreHighWater` and completes the gap-free interval `(initialCatchupHighWater, restoreHighWater]` before reopen.
- Proof of empty coordination state, complete participant disposition, post-restore hardening, external credential validation, secret-compliance refresh, backup confidentiality, and smoke verification is required before reopen.
- One durable recovery controller is the runtime authority for the release boundary. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` idempotently drives the internal `ready_to_reopen -> releasing -> finalized` transition, keeps ingress fail-closed until it applies and observes the quarantine release, and only then permits traffic. Checked-in recovery evidence is exported as an immutable projection after `finalized`; it is not a cross-system transaction participant.

`scoped_reset_restore` with surviving Coordination Redis is explicitly deferred and quarantined. It may be used only for isolated non-player-facing experiments or maintenance investigation under a future separate decision and proof package. Such experiments still follow the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence), but their pause, epoch, scope, or reset evidence must not satisfy backup readiness, traffic-open, promotion, or player-facing restore proof.

Ambiguous or mixed-timeline restore behavior is not allowed:

- operators must not restore PostgreSQL and restart everything without enforced quarantine and cold-start classification;
- surviving Redis must not be retained or merged with the older database;
- any player-facing restore that cannot prove environment-wide `cold_start_restore` remains quarantined; and
- tenant-local, game-instance-local, or region-local rewind from the whole-database artifact is unsupported, and tenant/game-instance/region pause or reset evidence cannot prove environment-wide recovery readiness.

`scoped_reset_restore` is a deferred future mode for quarantined experiments only. It requires a separate accepted design and complete region ownership, scope inventory, stale-state rejection, session policy, and reconciliation proof before it can become player-facing.

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
5. Prove empty Coordination Redis, record environment-wide `cold_start_restore`, and establish the durable recovery-controller state before any normal Game Session or automation worker can create fresh coordination state.
6. Capture immutable `initialCatchupHighWater` and complete gap-free erasure replay in `(artifactErasureHighWater, initialCatchupHighWater]`; during one bounded final cutover, capture immutable `restoreHighWater` and complete gap-free replay in `(initialCatchupHighWater, restoreHighWater]`. Complete offline participant convergence and epoch/fence reset. Before coordination initialization, reconcile both Account's durable restore-cutover evidence for Account-session and authority/delegation-lineage invalidation and the separate Game Session-owned evidence for gameplay-binding invalidation; recovery does not perform either invalidation itself.
7. Run post-restore hardening, external credential validation, secret-compliance evidence refresh, required sanitization and confidentiality checks, and an explicitly fenced restore-safe smoke profile. Do not start unrestricted normal workloads before `ready_to_reopen`.
8. After the controller reaches `ready_to_reopen`, call `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` and retry its idempotent reconciliation until it applies and observes quarantine release and reaches `finalized`. Open player traffic only after that observation. Export checked-in recovery and traffic-open projections afterward; repository evidence records the finalized release and is never an input to the release transaction.

## Local Development

- Restore with `dev-tools/restores/restore-db.sh` or `restore-latest-db.sh`.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services restart with Docker Compose.
- If Coordination Redis starts empty, treat it as a coordination reset event rather than expecting in-flight timers, retries, or sessions to survive.
- The compose stack includes `pg-dump-cron` running every 15 minutes to mirror the production schedule.
- For local clusters without cloud storage, operators may deploy `k8s/velero/minio.yaml` and run `dev-tools/backups/setup-local-backup.sh` to bootstrap MinIO plus Velero. `defaultVolumesToFsBackup` must remain `false`.

Docker Compose restore is not a reduced recovery mode. It must enter restore-safe quarantine, restore the environment-wide PostgreSQL artifact, clear Coordination Redis, reconcile Account's durable Account-session and authority/delegation-lineage invalidation result plus the separate Game Session-owned gameplay-binding invalidation result, reset every gameplay-region epoch and fence, converge every declared and enabled durable participant and external-effect family, run equivalent post-restore hardening, external-credential validation, secret-compliance refresh, and smoke checks, and reopen only through the same durable, idempotent `ready_to_reopen -> releasing -> finalized` controller gate and `continueRecovery(...)` contract as Kubernetes. When Kubernetes is unavailable, a Compose-native controller must provide those same durable semantics and remain fail-closed on an incomplete or ambiguous result; a one-shot command may only invoke or retry that controller and must not replace it.

## Backup Verification & Restoration Testing

- `verify-backups.sh` proves only that Velero backup artifacts exist and that optional pg-dump object storage is reachable. It does not prove immutable lineage, artifact readability, restore-tool compatibility, or player-facing readiness.
- Restore drills prove the artifact, restore tooling, canonical environment-wide `cold_start_restore` mode, and post-restore hardening flow actually produce a recoverable environment.
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
| **Kubernetes** | Enter restore-safe quarantine -> restore PostgreSQL from the online snapshot artifact -> prove empty Coordination Redis and target-environment credential rebinding -> establish the durable recovery controller -> capture immutable `initialCatchupHighWater` and replay `(artifactErasureHighWater, initialCatchupHighWater]` gap-free -> capture immutable final-cutover `restoreHighWater` and replay `(initialCatchupHighWater, restoreHighWater]` gap-free -> run environment-wide offline convergence and epoch/fence reset -> reconcile Account-owned Account-session and authority/delegation-lineage invalidation evidence plus separate Game Session-owned gameplay-binding invalidation evidence -> restore manifests with normal workloads still closed -> run confidentiality, post-restore hardening, and restore-safe smoke -> call `continueRecovery(...)` through `ready_to_reopen` and `releasing` -> reopen only after `finalized` -> export immutable checked-in projections only after `finalized` |
| **Docker Compose** | Enter restore-safe quarantine -> restore the environment-wide PostgreSQL snapshot -> clear Coordination Redis and rebind target-environment credentials -> capture immutable `initialCatchupHighWater` and replay `(artifactErasureHighWater, initialCatchupHighWater]` gap-free -> capture immutable final-cutover `restoreHighWater` and replay `(initialCatchupHighWater, restoreHighWater]` gap-free -> reconcile Account-owned Account-session and authority/delegation-lineage invalidation evidence plus separate Game Session-owned gameplay-binding invalidation evidence and reset every gameplay-region epoch/fence -> converge durable participants and external effects -> run confidentiality, post-restore hardening, credential/secret-compliance validation, and restore-safe smoke -> call `continueRecovery(...)` through `ready_to_reopen` and `releasing` -> reopen only after `finalized` -> export immutable checked-in recovery and traffic-open projections only after `finalized` |

Redis always uses AOF for crash recovery during runtime but is never restored from backup images. If Coordination Redis starts empty, treat it as a reset/cold-start scenario as described in the Redis architecture docs.

For diagnostic purposes, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into isolated throwaway Redis instances to inspect keys and coordination history during incident analysis. These snapshots are strictly read-only tools and must never be restored into live Coordination Redis clusters as rollback images.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Redis Reset and Recovery](./system-architecture-redis-reset-and-recovery.md)
- [Runbooks](./system-architecture-runbooks.md#recovery)
- [Deploy Preflight Policy](./system-architecture-deploy-preflight-policy.md)
- [Deployment Runbook](./system-architecture-deployment-runbook.md)
