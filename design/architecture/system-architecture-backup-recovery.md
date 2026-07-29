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
- Before opening the PostgreSQL snapshot transaction, the backup workflow reads and durably acknowledges the current committed external erasure-journal sequence as `preSnapshotJournalHighWater` solely as ordering proof. Only after that acknowledgement may it open one transactionally consistent database snapshot; inside that same snapshot it binds immutable `artifactErasureHighWater` to the greatest authoritative erasure-ledger sequence visible there. The candidate may be published when an immutable boundary-proof check establishes either `preSnapshotJournalHighWater >= artifactErasureHighWater`, or, when `preSnapshotJournalHighWater < artifactErasureHighWater`, an `interveningErasureCoverageProof` showing that every sequence in `(preSnapshotJournalHighWater, artifactErasureHighWater]` is represented exactly once with matching identity/digest in both the snapshot-visible erasure ledger and the external journal. Any gap, duplicate, unknown, or unverifiable intervening entry invalidates the candidate and causes the snapshot to be discarded and retried; neither observation is clamped or used as the artifact boundary in place of `artifactErasureHighWater`. Each artifact records both observations with their distinct sources and meanings alongside environment/database identity, snapshot time, schema and migration lineage, deployed service digests, backup-tool digest, and object-storage binding.
- Production Terraform deploys this CronJob automatically.
- Retention policy:
  - 24 hours of 15-minute dumps
  - 10 days of daily dumps
  - 3 weekly dumps
  - 3 monthly dumps
- Dumps are written to `firemud-pg-dumps` and may also upload to object storage when `PG_DUMP_BUCKET` is configured.
- In production, skipped object-storage uploads are a misconfiguration even if short-term dumps remain on PVC.
- Velero schedules back up Kubernetes manifests only, with `snapshotVolumes: false`.
- Backups are immutable until normal expiry and may contain subject data erased after their snapshot time. Under [ADR 0050](./decisions/adr-0050-versioned-export-retention-and-erasure-policy.md), Account commits terminal erasure to an immutable, monotonic overlay journal retained outside the PostgreSQL backup lineage. The canonical restore replay interval is fixed as `(artifactErasureHighWater, restoreHighWater]`, inclusive at `restoreHighWater`; the pre-snapshot journal observation is ordering evidence, and when it is lower than the snapshot-bound artifact high-water the immutable `interveningErasureCoverageProof` covers the interval between them, but neither observation is the artifact replay boundary. A deletion committed during or after snapshot creation is covered by that fixed interval and idempotent owner reconciliation only when it is at or below the captured `restoreHighWater`; later deletions use the normal online erasure consumer.

### Online Snapshot Contract

The backup artifact is one consistent PostgreSQL database view, not a tenant- or region-scoped gameplay artifact. Online backup correctness requires:

- one transactionally consistent snapshot covering every service schema in the declared database;
- one independently acknowledged pre-snapshot erasure-journal observation captured before the snapshot transaction opens, explicitly distinguished from the artifact replay boundary, with an immutable boundary proof: `preSnapshotJournalHighWater >= artifactErasureHighWater` is sufficient, while a lower pre-snapshot value requires `interveningErasureCoverageProof` for every sequence in `(preSnapshotJournalHighWater, artifactErasureHighWater]`, represented exactly once with matching identity/digest in the snapshot-visible ledger and external journal; any gap or ambiguity invalidates the candidate and requires a fresh snapshot rather than changing either boundary;
- immutable environment, database, schema/migration, service-digest, tool-digest, snapshot-time, and object-store lineage;
- immutable `artifactErasureHighWater` captured as the greatest authoritative erasure-ledger sequence visible inside the same PostgreSQL snapshot, not the pre-snapshot journal observation, then bound with the immutable artifact digest, snapshot identity, and applicable ordering or `interveningErasureCoverageProof` in one immutable manifest;
- one atomic or compare-and-set ready-publication record created only after the artifact bytes and manifest are durably stored; a crash, duplicate publication, missing object, mutable object, or digest/high-water mismatch leaves the candidate unpublished or quarantined and makes recovery reject it;
- artifact integrity and a restore-readability check rather than object-existence proof alone;
- no claim that the snapshot also preserves Coordination Redis, active sessions, queued transient work, or external provider state; and
- periodic production-equivalent proof that durable workflow and external-effect reconciliation can recover from an artifact captured while representative writes are active.

Cross-service workflows may be captured between durable steps, as they may be during an abrupt crash. The player-facing readiness boundary is therefore restore-time convergence, not recurring write quiescence. Every declared and enabled durable participant must be idempotently replayable, externally reconcilable, or deterministically terminalizable or invalidatable. A participant that is only durably fenced/disabled with retained backlog is represented as `fenced_disabled_backlog_retained`, but remains an explicit blocker to reopen rather than a qualifying convergence result.

## Recovery Controller Continuation

`recover` is a non-authorizing operation-creation API: it records the requested workflow and scope but cannot continue, authorize, or release that operation. Every mutating operation acquires the initial fence and maintenance lock and returns `maintenanceLockApplicability=REQUIRED` plus the non-empty controller-issued `maintenanceLockToken` through the protected issuance channel. The strictly observational no-effect dry-run exception returns `maintenanceLockApplicability=NOT_APPLICABLE`, omits the token, and finalizes directly; it can never call `continueRecovery` or `resume`. Null, blank, caller-chosen, sentinel, omitted-when-required, or supplied-when-not-applicable token representations fail closed without mutation. The public phase-continuation verb is `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`. The one gated release-boundary invocation uses `expectedPhase=ready_to_reopen`; callers retry that same tuple and never issue a public call for `releasing`. The controller requires the exact issued token, validates the immutable evidence, and idempotently reconciles the operation into `AWAITING_RESUME`; it does not authorize or perform release. The separate public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` likewise requires that exact token and uses persisted-form `expectedPhase=awaiting_resume` to record `RESUME_AUTHORIZED`, after which only the internal release phase may drive `releasing -> finalized`. Each internal phase transition is durable. A crash or retryable apply/readiness failure leaves the operation in its current phase and returns a retryable attempt outcome rather than caching a terminal result. An expected-phase, token, or evidence mismatch fails without mutation and is not cached as the operation result. Concurrent calls with the same tuple observe the same durable attempt or final result; conflicting tuples fail closed. Terminal public-transition idempotency records are keyed by verb plus the exact operation-owned tuple `(operationId, recorded scope, expected phase, lock identity, and evidence identity)`, not by `operationId` alone. The public surface consists of the durable non-authorizing `recover`, gated `continueRecovery`, authorization `resume`, and audited abandonment controls; pause, lock acquisition, phase selection, and success release remain internal phases that callers cannot invoke or skip directly.

## Artifact Erasure Replay Boundary

`artifactErasureHighWater` is transactionally bound to the immutable backup snapshot and is never recalculated from restored PostgreSQL. It is the greatest authoritative erasure-ledger sequence visible in that snapshot; `preSnapshotJournalHighWater` is a separately sourced ordering observation recorded before the snapshot and is not itself a replay boundary. When it is lower than the artifact boundary, the immutable backup manifest must contain `interveningErasureCoverageProof` for every sequence in `(preSnapshotJournalHighWater, artifactErasureHighWater]`, represented exactly once with matching identity/digest in the snapshot-visible ledger and external journal; a gap, duplicate, unknown, or ambiguous entry invalidates the artifact and keeps recovery quarantined. At recovery start, the controller captures immutable `initialCatchupHighWater` once. Recovery must complete the fixed initial replay interval `(artifactErasureHighWater, initialCatchupHighWater]` before entering final cutover. During one bounded final cutover, the erasure authority captures immutable `restoreHighWater` once as the final readiness boundary. The controller requires `restoreHighWater >= initialCatchupHighWater >= artifactErasureHighWater`; a lower value, unknown entry, or unprovable interval keeps recovery quarantined. Before `ready_to_reopen`, recovery must replay the one fixed interval `(artifactErasureHighWater, restoreHighWater]` into the restored environment, with the initial interval complete before cutover and only `(initialCatchupHighWater, restoreHighWater]` remaining for cutover. Neither boundary may move during replay.

Normal erasure acceptance may continue during initial catch-up beyond immutable `initialCatchupHighWater`; those later sequences are covered by the final cutover rather than by moving the initial boundary. After the initial interval is complete, readiness requires one bounded final cutover owned by the erasure authority: it fences or serializes new sequence assignment, captures immutable `restoreHighWater` once, completes only the remaining portion `(initialCatchupHighWater, restoreHighWater]`, and installs that fixed value as the restored environment's online erasure-consumer cursor before releasing the fence. If the cutover exceeds its configured budget before handoff, the controller keeps the fence and quarantine closed until the recorded final boundary and cursor handoff are reconciled; it never advances either boundary from a newly sampled current high-water. Missing or ambiguous capture, replay, or cursor-handoff state keeps recovery in `collecting` and retries the idempotent handoff. The restore overlay replay ends at the fixed `restoreHighWater`; deletions accepted after the proven handoff are consumed only by the normal online consumer/reconciliation path while player traffic remains closed until recovery is `finalized`.

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
- Recovery advances or recreates every gameplay epoch/fence and must obtain Account's durable restore-cutover evidence proving invalidation of all restored Account authority and `game-session-account-delegation` lineages, plus separate Game Session recovery evidence proving invalidation of all restored gameplay bindings, before any recovered session or normal workload is admitted. Recovery only observes/reconciles those owner-specific invalidation results; it is not a second Account or Game Session invalidation writer. Account Service is the sole writer of Account-owned issuer, account, tenant, membership-generation, and issued-token projections: recovery requests that cutover, awaits its durable completion, and verifies the returned freshness/generation evidence before continuing. It then obtains a safe disposition for every declared and enabled durable workflow and external-effect family and rebuilds coordination state only from restored durable authority plus new post-restore activity.
- Recovery captures immutable `initialCatchupHighWater`, then during one bounded final cutover captures immutable `restoreHighWater` and completes the one fixed replay interval `(artifactErasureHighWater, restoreHighWater]` before reopen. The controller may prove that interval in the two bounded portions `(artifactErasureHighWater, initialCatchupHighWater]` and `(initialCatchupHighWater, restoreHighWater]`; deletions later than `restoreHighWater` use the normal online consumer.
- Proof of empty coordination state, complete participant disposition, post-restore hardening, external credential validation, secret-compliance refresh, backup confidentiality, Account authority/issued-token projection rebuild, replay-domain quarantine/fencing and durable consume acknowledgement, and smoke verification is required before authorization.
- One durable recovery controller is the runtime authority for the release boundary. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses canonical `expectedPhase=ready_to_reopen` to idempotently reconcile the pre-release state into `AWAITING_RESUME`; it does not release quarantine. Public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` then uses persisted-form `expectedPhase=awaiting_resume` to record `RESUME_AUTHORIZED` without releasing the lock or traffic fence. Only the internal release phase may drive `releasing -> finalized`, after which traffic and immutable checked-in evidence projections may be exposed.

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

The following controller-backed sequence is target state and is unavailable until the durable recovery controller and its end-to-end proof path are implemented. Current operators must keep normal workloads stopped or restore-safe-fenced and use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback); they must not invoke the unavailable `continueRecovery` or `resume` controls.

Manual bootstrap example sequence (target state only):

1. Enter restore-safe quarantine as described in `system-architecture-post-restore-hardening.md` so player ingress, background processors, and outbound integrations cannot run with snapshot-era state.
2. Copy or download the desired dump.
3. Restore it into the target PostgreSQL pod with `psql`.
4. Restore manifests or Velero resources with normal application workloads held at zero replicas or under an enforced restore-safe startup gate; only infrastructure and maintenance Jobs required for recovery may run.
5. Prove empty Coordination Redis, record environment-wide `cold_start_restore`, and establish the durable recovery-controller state before any normal Game Session or automation worker can create fresh coordination state.
6. Capture immutable `initialCatchupHighWater` and complete the initial erasure replay interval `(artifactErasureHighWater, initialCatchupHighWater]` before final cutover. During one bounded final cutover, fence or serialize new erasure-sequence assignment, capture immutable `restoreHighWater`, complete `(initialCatchupHighWater, restoreHighWater]`, and hand off the fixed `restoreHighWater` as the online erasure-consumer cursor. Preserve downstream participant quarantine, epoch/fence reset, and durable-consume requirements throughout. Before coordination initialization, reconcile both Account's durable restore-cutover evidence for Account-session and authority/delegation-lineage invalidation and the separate Game Session-owned evidence for gameplay-binding invalidation; recovery does not perform either invalidation itself. Request that Account Service rebuild and verify the issuer, account, tenant, membership-generation, and exact issued-token projections from durable authority, await its durable completion, and verify the returned freshness/generation evidence. Then complete replay-domain quarantine/fence and durable consume proof before smoke or recovery authorization can pass. Deletions later than `restoreHighWater` use the normal online consumer.
7. Run post-restore hardening, external credential validation, secret-compliance evidence refresh, required sanitization and confidentiality checks, and an explicitly fenced restore-safe smoke profile. Do not start unrestricted normal workloads before `ready_to_reopen`.
8. After the controller reaches `ready_to_reopen`, call `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with `expectedPhase=ready_to_reopen` and retry its idempotent reconciliation until it reaches `AWAITING_RESUME`; it must not release quarantine. Call public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with persisted-form `expectedPhase=awaiting_resume`, then let the internal release phase apply and observe quarantine release through `releasing -> finalized`. Open player traffic only after `finalized`. Export checked-in recovery and traffic-open projections afterward; repository evidence records the finalized release and is never an input to the release transaction.

## Local Development

- Restore with `dev-tools/restores/restore-db.sh` or `restore-latest-db.sh`.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services restart with Docker Compose.
- If Coordination Redis starts empty, treat it as a coordination reset event rather than expecting in-flight timers, retries, or sessions to survive.
- The compose stack includes `pg-dump-cron` running every 15 minutes to mirror the production schedule.
- For local clusters without cloud storage, operators may deploy `k8s/velero/minio.yaml` and run `dev-tools/backups/setup-local-backup.sh` to bootstrap MinIO plus Velero. `defaultVolumesToFsBackup` must remain `false`.

The target-state Docker Compose restore is not a reduced recovery mode. It must enter restore-safe quarantine, restore the environment-wide PostgreSQL artifact, clear Coordination Redis, reconcile Account's durable Account-session and authority/delegation-lineage invalidation result plus the separate Game Session-owned gameplay-binding invalidation result, reset every gameplay-region epoch and fence, converge every declared and enabled durable participant and external-effect family, run equivalent post-restore hardening, external-credential validation, secret-compliance refresh, and smoke checks, then use `continueRecovery(... expectedPhase=ready_to_reopen ...)` to reach `AWAITING_RESUME`, public `resume(... expectedPhase=awaiting_resume ...)` to record `RESUME_AUTHORIZED`, and the internal release phase to reach `finalized`. When Kubernetes is unavailable, a Compose-native controller must provide those same durable semantics and remain fail-closed on an incomplete or ambiguous result; a one-shot command may only invoke or retry that controller and must not replace it. Until that controller is implemented and proved, local restore remains fenced under the same [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).

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

This table summarizes the target controller-backed workflow, not currently executable operator commands. The fail-closed current path is the [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).

| Environment | Steps |
| --- | --- |
| **Kubernetes** | Enter restore-safe quarantine -> restore PostgreSQL from the online snapshot artifact -> prove empty Coordination Redis and target-environment credential rebinding -> establish the durable recovery controller -> capture immutable `initialCatchupHighWater` -> replay and prove the bounded initial interval `(artifactErasureHighWater, initialCatchupHighWater]` -> during one bounded final cutover capture immutable `restoreHighWater` and replay only `(initialCatchupHighWater, restoreHighWater]` -> run environment-wide offline convergence and epoch/fence reset -> reconcile Account-owned Account-session and authority/delegation-lineage invalidation evidence plus separate Game Session-owned gameplay-binding invalidation evidence -> restore manifests with normal workloads still closed -> run confidentiality, post-restore hardening, and restore-safe smoke -> `continueRecovery(... expectedPhase=ready_to_reopen ...)` to `AWAITING_RESUME` -> public `resume(... expectedPhase=awaiting_resume ...)` -> internal release through `finalized` -> reopen only after `finalized` -> export immutable checked-in projections only after `finalized` |
| **Docker Compose** | Enter restore-safe quarantine -> restore the environment-wide PostgreSQL snapshot -> clear Coordination Redis and rebind target-environment credentials -> capture immutable `initialCatchupHighWater` -> replay and prove the bounded initial interval `(artifactErasureHighWater, initialCatchupHighWater]` -> during one bounded final cutover capture immutable `restoreHighWater` and replay only `(initialCatchupHighWater, restoreHighWater]` -> reconcile Account-owned Account-session and authority/delegation-lineage invalidation evidence plus separate Game Session-owned gameplay-binding invalidation evidence and reset every gameplay-region epoch/fence -> converge durable participants and external effects -> run confidentiality, post-restore hardening, credential/secret-compliance validation, and restore-safe smoke -> `continueRecovery(... expectedPhase=ready_to_reopen ...)` to `AWAITING_RESUME` -> public `resume(... expectedPhase=awaiting_resume ...)` -> internal release through `finalized` -> reopen only after `finalized` -> export immutable checked-in recovery and traffic-open projections only after `finalized` |

Redis always uses AOF for crash recovery during runtime but is never restored from backup images. If Coordination Redis starts empty, treat it as a reset/cold-start scenario as described in the Redis architecture docs.

For diagnostic purposes, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into isolated throwaway Redis instances to inspect keys and coordination history during incident analysis. These snapshots are strictly read-only tools and must never be restored into live Coordination Redis clusters as rollback images.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Redis Reset and Recovery](./system-architecture-redis-reset-and-recovery.md)
- [Runbooks](./system-architecture-runbooks.md#recovery)
- [Deploy Preflight Policy](./system-architecture-deploy-preflight-policy.md)
- [Deployment Runbook](./system-architecture-deployment-runbook.md)
