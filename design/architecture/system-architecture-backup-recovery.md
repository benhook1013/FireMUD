# FireMUD System Architecture: Backup & Disaster Recovery

This document defines the backup schedule and disaster recovery procedures for FireMUD. Backup expectations are defined by environment class:

- **production**: scheduled backups and verification are mandatory.
- **hobby-self-hosted**: backups are mandatory with a minimum baseline (at least daily logical backup, at least 7 daily restore points retained, and at least one restore drill every 30 days). Operators may choose cadence/automation above this floor.
- **staging**: disposable by default with no scheduled backups unless explicitly enabled for specific goals.
- **local-dev / pr-preview / dev-demo-cluster**: ad hoc or no-backup posture unless explicitly upgraded. `pr-preview` persists mutable state only for the lifetime of the PR and loses that state if the preview node or its storage is lost.

Staging is treated as **disposable by default**: it does not run the production backup CronJobs unless operators explicitly install staging-specific schedules. Operators may temporarily restore staging from production backups for disaster recovery rehearsals or investigations; when doing so, staging must follow the same post-restore secret hardening flow before it is considered player-facing again (see [Post-Restore Secret Hardening](#post-restore-secret-hardening)).
`hobby-self-hosted` restores that return an environment to player-facing status must also execute the same core hardening controls (JWT/JWKS rotation, DB credential rotation, certificate reissuance, and external credential validation) before reopening traffic.

---

## Implementation Notes

The main body of this document describes the target-state backup workflow. Current implementation may lag the target state in a few areas:

- `PauseTicksForScope` / `ResumeTicksForScope` support pausing by `tenant_id` + `game_instance_id` today; `region_id` scoping exists in the proto contract but is not yet enforced end-to-end.
- Backup-related spans and metrics should still use the target-state names and units documented here so dashboards and alert rules remain stable as scope support is expanded.
- Player-facing prod-like environments are not considered coordinated-backup-ready until automated backups invoke `PauseTicksForScope` / `ResumeTicksForScope` with canonical `tenant_id + region_id` scope end-to-end. Alias scope remains a migration aid, not a steady-state operating mode.
- Until that convergence is complete, production releases that would depend on restore-point recovery rather than binary rollback are non-compliant for promotion.
- The alias-scope migration notes in this document are temporary implementation-bridge guidance. Once canonical `tenant_id + region_id` pause scope is enforced end-to-end, these migration-only notes and phase descriptions should be removed so the architecture returns to one steady-state scope contract.

## PostgreSQL Logical Backups

- A `firemud-pg-dump` CronJob (defined in `k8s/postgres/pg-dump-cronjob.yaml`) runs **every 15 minutes** and stores compressed SQL dumps.
- The CronJob authenticates to the Game Session control plane to invoke `PauseTicks` / `ResumeTicks` using a dedicated Kubernetes service account and a narrowly-scoped mTLS client identity. NetworkPolicies and RBAC restrict this Job so only the backup workflow can call tick-pausing APIs.
- The production Terraform modules automatically deploy this CronJob. See [`k8s/terraform-production`](../../k8s/terraform-production).
- Retention policy:
  - **24 hours** of 15‑minute dumps
  - **10 days** of daily dumps
  - **3 weekly** dumps
  - **3 monthly** dumps
- The CronJob writes to a persistent volume claim `firemud-pg-dumps` and runs
  a script (`pg-dump.sh`) that enforces the retention policy. Dumps are stored under `15min`, `daily`, `weekly`, and `monthly` directories. The environment
  variable `PG_DUMP_BUCKET` must be set to enable uploads; `PG_DUMP_ENDPOINT` is optional and is used only when targeting an S3-compatible endpoint such as MinIO. If `PG_DUMP_BUCKET` is unset, uploads are skipped. In production, skipping uploads is treated as a misconfiguration: it is acceptable to keep short-term dumps on the PVC, but the backup pipeline is not considered healthy unless object storage uploads are enabled and verified. When uploads are enabled, the script uploads each dump to the specified bucket. The same script is available for local use as `dev-tools/backups/pg-dump-rotate.sh`.
- Velero schedules defined in `k8s/velero/schedule.yaml` back up only Kubernetes manifests (`snapshotVolumes: false`). See [k8s/velero/README.md](../../k8s/velero/README.md) for installation details.
- Copy `k8s/velero/values.example.yaml` to `values.yaml` and configure your object storage bucket. Example:

```yaml
configuration:
  provider: aws
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    bucket: firemud-backups
```

Always leave `defaultVolumesToFsBackup` set to `false` so Velero backs up only Kubernetes manifests and not persistent volume contents.

### Coordinated Tick Pausing

PostgreSQL dumps must capture a consistent view of gameplay state. Before a `pg_dump` begins, the Game Session Service exposes `PauseTicks` and `ResumeTicks` gRPC commands. The backup workflow:

1. Calls `PauseTicks` with a reason string. This sets a pause request for the selected scope, blocks new gameplay command intake for that scope, and prevents new durable tick-batch allocation while allowing any already in-flight executor work to drain, fail, or lose lease.
2. Polls `GetRegionTickStatus` until the service reports the canonical `PAUSED` state for every affected region, which means `commandIntakeBlocked=true`, `batchAllocationBlocked=true`, and no executor in the scope can create new coordination state under the pre-pause epoch.
3. Starts `pg_dump` immediately. Ticks may resume as soon as the dump command starts because PostgreSQL snapshots the data at launch time.
4. Invokes `ResumeTicks` so command intake and tick scheduling resume for the scope.

Operational constraints:

- Tick pausing is part of the production backup path, so it must be bounded and observable. Backup tooling should track:
  - How long it takes for a scope to transition to `PAUSED` (pause latency).
  - How long the scope remains paused (pause duration), even though the dump itself does not require ticks to stay paused.
- Backup and reset tooling must use the same pause/status contract. `PauseTicks` / `ResumeTicks` and `GetRegionTickStatus` are the canonical control-plane boundary; backup automation must not invent weaker “scheduler paused but command intake still open” semantics.
- Pause scope should be limited to the smallest safe blast radius (prefer tenant- or region-scoped pausing over global pausing) so backups do not create unnecessary player impact.
- If a pause does not reach `PAUSED` within a documented budget, the backup Job should fail fast (skip the dump) and alert operators rather than silently holding the game in a paused state or producing an inconsistent backup.
  - Recommended budget: `max_pause_wait = max(10s, 2 * tick_interval_ms)` for the affected scope.
  - Recommended alert threshold: page if `max_pause_wait` is exceeded for any production backup attempt, or if the scope remains `PAUSED` for longer than `3 * max_pause_wait` (indicating the resume step failed).
  - Severity contract: when the affected scope is player-facing, “pause wait exceeded” and “scope still paused” alerts are **P0** because command processing is blocked for that scope. Freshness/verification failures remain `P1`.
  - Backup tooling should expose pause metrics with unambiguous units (for example `backup_tick_pause_wait_seconds` and `backup_tick_pause_duration_seconds`) and include `tenantId`/`regionId` labels only when the pause scope is already bounded to avoid high cardinality.
- Backup tooling should also expose matching budget gauges (`backup_tick_pause_wait_budget_seconds` and `backup_tick_pause_duration_budget_seconds`) as first-class emitted metrics for each backup attempt/scope so alert rules do not hardcode thresholds that can drift from the documented cadence-derived budgets.
  - These gauges must already carry the same scope labels as the observed pause metrics (`scope_type`, `tenantId`, `regionId` when bounded). Prometheus rules may derive fallback breach indicators from them, but should not try to reconstruct scoped budget gauges from `tick_interval_ms` alone because that loses alias-scope context and can break label matching.
- Manual alias-scoped maintenance operations must also write an audit record with the requested alias scope and the resolved region set. That audit record is the authoritative locator for an individual alias-scoped pause/resume action; metrics remain intentionally low-cardinality.
  - For canonical storage and later incident lookup, write this record under the environment recovery namespace when the operation is part of restore/drill automation (for example `design/operations/deployments/<environment>/recovery/<recovery-ref>.json`) or under the environment maintenance namespace when it is a standalone maintenance action (for example `design/operations/deployments/<environment>/maintenance/<maintenance-ref>.json`).

For convenience, `dev-tools/backups/firemud-backup.sh` automates these steps by pausing ticks, waiting until the service is paused, running `pg_dump`, and then calling `ResumeTicks`.

Velero continues backing up Kubernetes manifests only and does **not** pause any services. Tick pausing is required only at the start of `pg_dump`, not for its entire runtime.

#### Tick Pause Scope Contract (Normative)

Tick pause/resume APIs support multiple ways to identify scope. To keep operators and automation predictable, the contract is:

- The **canonical scope** is `tenant_id` + `region_id`. This is the scope used by tick health dashboards, SLOs, and incident runbooks.
- `game_instance_id` is an **alias scope** that is allowed only when a game instance maps cleanly to a single tick region. It exists primarily for early implementations and for backup tooling that is tied to game-instance boundaries.
- Requests must set `tenant_id` and must set exactly one of:
  - `region_id` (preferred), or
  - `game_instance_id` (alias)
- If both `region_id` and `game_instance_id` are set, the request is rejected as `INVALID_ARGUMENT`.
- If neither is set, the request is rejected as `INVALID_ARGUMENT`.

Backups should use `region_id` scoping wherever possible to minimize blast radius; use the alias only when the deployment does not yet expose region-scoped pause controls end-to-end.

##### Player-Facing Coordinated Backup Gating (Normative)

To keep backup alerts actionable and blast radius explicit:

- Automated coordinated backups in production and other player-facing prod-like environments must use canonical `tenant_id + region_id` scope.
- Until canonical `tenant_id + region_id` scope is enforced end-to-end, production and other player-facing prod-like environments must fail coordinated-backup readiness for live traffic. During that interim state, only quarantined drills, detached restore rehearsals, and other non-player-facing maintenance may rely on backup automation that still uses alias scope.
- Alias-scoped `game_instance_id` pause/resume is allowed only for:
  - non-player-facing restore drills,
  - quarantined staging rehearsals,
  - manual operator workflows that record explicit scope-resolution evidence.
- If an environment cannot pause by `region_id` end-to-end, it must fail coordinated-backup readiness for player-facing use rather than page operators with an unresolved alias-scoped `P0`.
- Manual alias-scoped operations must write an audit record that includes:
  - the requested alias scope,
  - the resolved `tenant_id`,
  - the resolved `region_id` set,
  - the actor or job identity,
  - start and end timestamps.

Example audit record shape:

```json
{
  "schemaVersion": "backup-maintenance-record/v1",
  "scopeType": "game_instance_alias",
  "requestedScope": {
    "tenant_id": "tenant-a",
    "game_instance_id": "gi-west-1"
  },
  "resolvedScope": {
    "tenant_id": "tenant-a",
    "region_ids": ["region-west-1"]
  },
  "actor": "firemud-pg-dump",
  "startedAt": "2026-03-13T02:15:00Z",
  "endedAt": "2026-03-13T02:15:08Z"
}
```

Evidence schema versions used in this document:

- `backup-maintenance-record/v1` for alias-scope maintenance and pause-scope audit evidence.
- `recovery-record/v1` for canonical player-facing restore evidence.
- `traffic-open-record/v1` for `hobby-self-hosted` first-live and reopen evidence.

#### Tick Pause Scope Migration Plan (Normative)

To remove long-term ambiguity between alias and canonical scope, the control-plane migration should follow explicit phases:

1. **Phase A (current): dual accept**
   - Accept `region_id` and `game_instance_id` (exactly one).
   - Emit usage metrics/counters for alias-scope requests and include a deprecation warning in control-plane logs for `game_instance_id` requests.
   - Restriction: automated coordinated backups for player-facing prod-like environments must already use `region_id`; alias acceptance in this phase is only for detached maintenance and migration support.
2. **Phase B: dual accept + warning enforcement**
   - Keep request acceptance unchanged.
   - Require dashboards/alerts for alias-scope usage so operators can verify clients and jobs are migrating.
3. **Phase C: canonical required**
   - Reject `game_instance_id`-only pause/resume requests with `INVALID_ARGUMENT`.
   - Require `region_id` for all production backup and incident automation.

Exit criteria for Phase C:

- Backup jobs in all prod-like environments invoke pause/resume with `tenant_id` + `region_id`.
- Tick incident tooling and runbooks no longer rely on `game_instance_id` fallback.
- Alias-scope usage metric is zero for a full release window before cutover.

For local clusters without cloud storage, deploy the `k8s/velero/minio.yaml` manifest and configure Velero with a local backup location:

```yaml
configuration:
  provider: aws
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    name: local
    provider: aws
    bucket: firemud-backups
    config:
      region: minio
      s3Url: http://minio.minio.svc.cluster.local:9000
      insecureSkipTLSVerify: true
credentials:
  useSecret: true
  existingSecret: velero-minio-creds
```

Run `dev-tools/backups/setup-local-backup.sh` to deploy MinIO, create the `firemud-backups` bucket, and install Velero automatically. Keep `defaultVolumesToFsBackup` disabled to avoid saving PVC data. Refer to [Developer Setup](../../DEVELOPER_SETUP.md#backing-up-the-local-database) for local database backup tips.

- If the database service fails completely:
  1. Restore the most recent `pg_dump` file from the `firemud-pg-dumps` volume or object store.
  2. Choose and record exactly one coordination recovery mode before services resume:
     - `cold_start_restore`: Coordination Redis is intentionally empty for the restored environment and is treated as a cold-start/reset event.
     - `scoped_reset_restore`: surviving Coordination Redis state is treated as potentially inconsistent with the restored PostgreSQL snapshot and must be fenced/reset using the Coordination Reset Model before player traffic resumes.
  3. Restart services only after the chosen coordination recovery mode's required gates have run.
  4. Coordination state is treated as reset-tolerant and is rebuilt as services run (where possible) from PostgreSQL state and new activity as described in the Redis architecture and runbooks; cache/rate-limit keys refill on demand.
     - Reset-sensitive session prefixes (`session:game:*`, `session:auth:*`) may be dropped as part of this rebuild; expect player re-login and internal token re-authentication where applicable.

## Redis Persistence

- Redis stores only **transient state**:
  - Coordination Redis stores volatile coordination state (ticks, locks, timers, sessions) and uses AOF for crash recovery while the cluster is running.
  - Cache/Rate-Limit Redis stores best-effort caches and rate-limit counters and is not treated as durable.
- Redis is **not restored** from backup during a cold start. If Coordination Redis starts empty (for example after a reset), treat it as a coordination reset event and follow the Coordination Reset Model rather than attempting to “restore Redis”. See [Failover vs Cold Start vs Reset](./system-architecture-redis-reset-and-recovery.md#failover-vs-cold-start-vs-reset) for what “rebuild from PostgreSQL” does and does not mean.
  In development a `redis-coord-data` volume can persist the Coordination Redis AOF between container restarts. Treat AOF restore as a debugging tool: restore into an isolated, throwaway Redis instance for inspection, and only restore into a live dev stack when services and Lua scripts match the AOF’s originating version.

### Redis AOF Reset on Deployment

Redis is treated as a **transient coordination layer** in terms of data authority (PostgreSQL owns canonical game data), but in staging and production it is also a **long-lived availability dependency**. The lifecycle of Redis and its AOF differs by environment:

- **Development and ephemeral test environments**
  - A Kubernetes Job (`k8s/helm/firemud/templates/redis-aof-reset-job.yaml`) may be enabled to wipe Coordination Redis data on each Helm install or upgrade.
  - This guarantees a clean slate between runs so stale gameplay state, tick locks, or timers do not leak across test cycles.
  - This behavior is appropriate for local/dev stacks and short‑lived preview environments where all games are disposable and no replay or uptime guarantees are made.

- **Staging and production-equivalent environments**
  - Redis AOF files and volumes are **not wiped as part of normal Helm upgrades**. Application deployments roll out while Redis keeps its in‑memory state and AOF, so active sessions, tick queues, and timers survive app releases.
  - Resetting Redis (by deleting its AOF and/or volumes) is treated as an explicit **operational maintenance action** equivalent to a “world restart”. Operators must expect:
    - All active sessions to terminate.
    - All volatile tick state, timers, and queues to be discarded.
    - Games to restart from authoritative PostgreSQL state on next login.
  - Any workflow that resets Redis in staging/production must be documented as a runbook with clear player‑impact notes; it is not part of the default CI/CD pipeline.
  - The **scope** of any reset (single region, single tenant, or entire cluster) must follow the guidelines in the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model) so that player impact and recovery behavior are predictable.

This behavior is distinct from **failover**:

- **Failover (node crash or leader change):** Redis pods restart or leadership moves, but AOF files and replication state are preserved. Tick locks and `tick:{tenantRegionTag}:pending` entries survive so the Game Session Service can safely **replay or complete** in-flight ticks using idempotent domain logic.
- **Cold start (Redis starts empty):** treat as a coordination reset event; replay is not possible because the coordination history is missing. Services re-establish coordination as new activity occurs, and operators may need to run explicit coordination reset tooling to clear partial or mis-keyed remnants.
- **Deployment (Helm upgrade) in staging/production:** Application pods roll forward while Redis keeps its AOF and in‑memory state. In‑flight ticks and sessions remain active across deployment.

## Kubernetes Production

- **Velero** backs up Deployments, StatefulSets, ConfigMaps, and Secrets (but not volume snapshots).
  - Restoration process (scripted):
    1. Use `dev-tools/restores/restore-cluster.sh <backup-name>` to restore the latest `pg_dump` and Kubernetes manifests into the quarantined target namespace.
    2. Treat that script as a restore-bootstrap step only unless it explicitly documents the full coordination recovery gate and post-restore hardening flow for the target environment. “Script completed” is not by itself sufficient evidence to reopen traffic.
    3. Before player traffic can reopen, run the required restore-mode selection, post-restore coordination recovery gate, secret hardening, external credential validation, and smoke verification defined later in this document.
    4. Set `FIREMUD_K8S_NAMESPACE` if restoring to a non-default namespace.
  - Restoration process (manual):
    1. Copy the desired dump out of the PostgreSQL pod:

       ```bash
       kubectl cp <namespace>/<pg-pod>:/backups/latest.sql.gz ./latest.sql.gz
       ```

    2. Restore it into the target PostgreSQL pod:

       ```bash
       gunzip -c latest.sql.gz | kubectl exec -i <postgres-pod> -- psql -U "$FIREMUD_POSTGRES_USER" "$FIREMUD_POSTGRES_DB"
       ```

    3. Restart services so they pick up the restored database:

       ```bash
       kubectl rollout restart deployment --all -n <namespace>
       kubectl rollout restart statefulset --all -n <namespace>
       ```

       Wait for rollouts to complete before marking restore steps successful:

       ```bash
       kubectl rollout status deployment --all -n <namespace>
       kubectl rollout status statefulset --all -n <namespace>
       ```

    4. Treat the rollout above as the database-restore/bootstrap step only. Before player traffic can reopen, operators must still select `cold_start_restore` or `scoped_reset_restore`, complete the corresponding coordination recovery gate, run post-restore hardening, validate external credentials, and pass smoke verification.

    5. If dumps live in `PG_DUMP_BUCKET`, download them first with:

       ```bash
       aws s3 cp s3://$PG_DUMP_BUCKET/<path> ./dump.sql.gz
       ```

       Add `--endpoint-url` for MinIO-backed buckets as needed.

### Restore Mode Selection (Normative)

Every restore that rewinds PostgreSQL must select one explicit Coordination Redis recovery mode before the environment is allowed to reopen:

- `cold_start_restore`
  - Use when Coordination Redis for the target environment is known to be empty because the PVC/data directory was intentionally replaced, lost, or wiped for the recovery event.
  - Required proof:
    - the Coordination Redis deployment came up with an empty keyspace for coordination prefixes,
    - the environment then followed the documented cold-start/reset behavior before traffic reopen, and
    - the same post-restore hardening, external credential validation, and smoke-verification evidence required for scoped resets is present before player traffic reopens.
- `scoped_reset_restore`
  - Use when Coordination Redis state may survive while PostgreSQL has been rewound to an earlier snapshot.
  - Required proof:
    - operators ran the authoritative reset handshake for the affected scope (`pause -> epoch bump -> reset -> ledger/command convergence -> init-meta -> smoke-check -> resume`), and
    - reset-sensitive session/auth state was either invalidated or explicitly handled according to reset policy before traffic reopen.

Ambiguous restore behavior is not allowed:

- Operators must not simply “restore PostgreSQL and restart everything” while leaving it undefined whether surviving coordination/session state is trusted.
- Any player-facing restore that cannot prove one of the two modes above is non-compliant and must remain quarantined.

## Local Development

- Backups are restored using `dev-tools/restores/restore-db.sh` with a snapshot file.
- `dev-tools/restores/restore-latest-db.sh` can fetch the newest dump from the object
  store and restore it automatically when `PG_DUMP_BUCKET` and
  `PG_DUMP_ENDPOINT` are configured.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services are restarted with **Docker Compose**.
- If Coordination Redis starts empty (for example after wiping volumes), treat it as a coordination reset event and rely on the documented reset behavior rather than expecting in-flight timers/retries/sessions to survive.
- The compose stack includes a `pg-dump-cron` service running
  `dev-tools/backups/pg-dump-rotate.sh` every 15 minutes to mirror the production
  backup schedule.

---

## Backup Verification & Restoration Testing

- The `k8s/velero/verify-backups-cronjob.yaml` CronJob runs nightly at **04:00**
  and executes `dev-tools/backups/verify-backups.sh` to ensure recent snapshots are present in
  the object store. The script also verifies that the latest PostgreSQL dump
  exists in `PG_DUMP_BUCKET`, that the dump is restorable by the supported recovery tooling, and that the latest eligible backup set can satisfy the recovery contract expected by the environment class. Existence-only checks are insufficient for player-facing recovery evidence. This
  CronJob is installed automatically by the production Terraform modules. See [`k8s/terraform-production`](../../k8s/terraform-production) for the deployment configuration.
- Operators should periodically test recovery by restoring a snapshot into a
  throwaway namespace with `dev-tools/restores/restore-cluster.sh <backup-name>
  <namespace>` (or by setting `FIREMUD_K8S_NAMESPACE`) and driving the restore through quarantine, post-restore hardening, external credential validation, and smoke verification so the drill produces the same canonical recovery record required for a player-facing reopen. A manual workflow
  `.github/workflows/manual-backup-restore.yml` can run these checks on
  demand from the GitHub Actions UI only for explicit throwaway drill targets using dedicated low-privilege credentials and GitHub Environment approvals. It must not restore into live staging or production namespaces, and it must not hold credentials capable of mutating the currently player-facing namespace or cluster boundary. See [Operational Runbooks](./system-architecture-runbooks.md#recovery) for step-by-step instructions.
- Artifact verification and restore proof are separate requirements:
  - `verify-backups.sh` proves backup artifacts exist, are readable, and remain compatible with supported recovery tooling.
  - restore drills prove the artifacts, restore tooling, restore-mode selection, and post-restore hardening flow actually produce a recoverable environment.
- Every successful restore drill must record:
  - selected restore mode (`cold_start_restore` or `scoped_reset_restore`),
  - restore-tool success,
  - smoke success,
  - required post-restore hardening and validation results for the drill scope,
  - immutable evidence references.
- Each environment boundary (staging, production) normally uses the namespace name `firemud` in its own cluster context. `FIREMUD_K8S_NAMESPACE` is an explicit override for throwaway restore drills and non-default restore targets.

### Backup Observability and Alerts

Backup and verification jobs must emit simple, environment-agnostic metrics so operators can see whether the pipeline is healthy:

- `backup_last_success_timestamp_seconds` – Unix timestamp of the last successful PostgreSQL logical backup (`pg_dump`) for the environment.
- `backup_verify_last_success_timestamp_seconds` – Unix timestamp of the last successful verification run from `verify-backups.sh` (for example, verifying that recent dumps exist in the object store).
- `backup_restore_drill_last_success_timestamp_seconds` – Unix timestamp of the last successful restore drill that completed restore-mode selection, restore execution, and post-restore validation for the environment class/binding.
- `backup_restore_drill_total{result="success"|"failure",mode}` – counts restore drill executions by result and chosen restore mode (`cold_start_restore` or `scoped_reset_restore`).
- Coordinated pause/resume safety metrics (to ensure backups do not cause prolonged player-visible degradation):
  - `backup_tick_pause_wait_seconds{scope_type,tenantId?,regionId?}` – time from `PauseTicks` request to `PAUSED`.
  - `backup_tick_pause_duration_seconds{scope_type,tenantId?,regionId?}` – duration of the tick pause window for a backup attempt (including wait-for-paused time).
  - `backup_tick_pause_wait_budget_seconds{scope_type,tenantId?,regionId?}` – the expected wait budget for the scoped backup attempt, normally derived from `max(10s, 2 * tick_interval_ms)` for canonical region scope.
  - `backup_tick_pause_duration_budget_seconds{scope_type,tenantId?,regionId?}` – the expected pause-duration budget for the scoped backup attempt, normally derived as a small multiple of `backup_tick_pause_wait_budget_seconds` (default target-state guidance: `3x`).
  - `backup_ticks_paused{scope_type,tenantId?,regionId?}` – gauge indicating whether ticks are currently paused for a given scope.
  - `backup_commands_queued_during_pause_total{scope_type,tenantId?,regionId?}` – count of commands queued while ticks were paused (helps detect unbounded backlog growth during long pauses).
  - `backup_pause_attempts_total{scope_type,result}` – counts pause/resume control attempts by result (`paused`, `resume_failed`, `timeout`, `invalid_scope`, `transport_error`).
  - `backup_pause_scope_alias_requests_total{alias="game_instance_id"}` – counts backup pause/resume requests that still rely on alias scope instead of canonical `region_id`.
- Optional per-job counters such as:
  - `backup_run_total{result="success"|"failure"}` – counts of backup Job executions.
  - `backup_verify_run_total{result="success"|"failure"}` – counts of verification Job executions.

For Alertmanager-down fallback and dashboard consistency, Prometheus should also publish derived breach indicators from those emitted metrics:

- `backup_pipeline_recent_backup_slo_breached` – boolean-like recording for “no successful backup within the configured freshness window”.
- `backup_pipeline_recent_verification_slo_breached` – boolean-like recording for “no successful verification within the configured freshness window”.
- `backup_pipeline_recent_restore_drill_slo_breached` – boolean-like recording for “no successful restore drill within the configured restore-proof freshness window”.
- `backup_tick_pause_wait_budget_breached{scope_type,tenantId?,regionId?}` – derived from `backup_tick_pause_wait_seconds > backup_tick_pause_wait_budget_seconds`.
- `backup_tick_pause_duration_budget_breached{scope_type,tenantId?,regionId?}` – derived from `backup_tick_pause_duration_seconds > backup_tick_pause_duration_budget_seconds`.
- `backup_ticks_paused_budget_breached{scope_type,tenantId?,regionId?}` – derived from “scope remains paused while the emitted pause-duration budget is exceeded”, so fallback and alerting do not rely on a fixed wall-clock constant.

Prometheus and Alertmanager should expose and alert on these metrics using rules along the lines of:

- **Missed backups (P1)**
  - Expression: “no successful backup in the last N minutes” (for example, `time() - backup_last_success_timestamp_seconds > 90 * 60` in production).
  - Labels: `service="postgres-backup"`, `severity="P1"`, `owner="infra"`, `runbook="design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing"`.
- **Missed verification (P1/P2)**
  - Expression: “no successful verification in the last 24h” (for example, `time() - backup_verify_last_success_timestamp_seconds > 24 * 60 * 60`).
  - Labels similar to the backup alert, with a clear `runbook` annotation.
- **Missed restore proof (P1)**
  - Expression: “no successful restore drill in the required window” (for example, `time() - backup_restore_drill_last_success_timestamp_seconds > 30 * 24 * 60 * 60` for production-like environments unless a stricter environment overlay is documented).
  - Labels: `service="postgres-backup"`, `severity="P1"`, `owner="infra"`, `runbook="design/architecture/system-architecture-backup-recovery.md#backup-verification--restoration-testing"`.
- **Backup pause too long (P0 for player-facing scopes)**
  - Expression: pause wait or pause duration exceeds the emitted per-scope budget (for example `backup_tick_pause_wait_seconds > backup_tick_pause_wait_budget_seconds` or `backup_tick_pause_duration_seconds > backup_tick_pause_duration_budget_seconds`).
  - Labels: `service="postgres-backup"`, `severity="P0"` when the affected scope is player-facing and `severity="P1"` otherwise, `owner="infra"`, `runbook="design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing"`.
- **Backup scope stuck paused (P0 for player-facing scopes)**
  - Expression: a pause gauge remains asserted after the emitted pause-duration budget has been exceeded (for example `backup_ticks_paused == 1 and backup_tick_pause_duration_seconds > backup_tick_pause_duration_budget_seconds`).
  - Labels: `service="postgres-backup"`, `severity="P0"` when the affected scope is player-facing and `severity="P1"` otherwise, `owner="infra"`, `runbook="design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing"`.
- **Legacy alias-scope usage (P2)**
  - Expression: `increase(backup_pause_scope_alias_requests_total[24h]) > 0` in production-like environments after canonical region scope is expected.
  - Labels: `service="postgres-backup"`, `severity="P2"`, `owner="infra"`, `runbook="design/architecture/system-architecture-backup-recovery.md#tick-pause-scope-migration-plan-normative"`.

Grafana dashboards under `design/observability/grafana` should include a small “Backups” section or dedicated dashboard that visualizes:

- The age of the last successful backup and verification.
- The age of the last successful restore drill.
- Recent backup/verify success vs failure counts.
- Restore drill results by recovery mode.
- Tick pause wait, pause duration, alias-scope usage, and queue growth signals during backup windows.

These signals allow operators to treat backup artifact freshness, restore proof, and backup safety as first-class SLOs alongside tick, Redis, and player experience SLOs.

#### Scope Migration Behaviour for Backup Signals

Until `region_id` is enforced end-to-end, backup dashboards and alerts must make alias-scope state explicit rather than silently pretending it is canonical:

- `scope_type` is always required and should distinguish `region`, `tenant`, `game_instance_alias`, and any broader maintenance scope the environment supports.
- During Phase A/B:
  - `tenantId` should still be present when known.
  - `regionId` may be absent for alias-scoped pause/resume attempts.
  - `backup_pause_scope_alias_requests_total` must increment for every alias-scoped attempt.
  - Traces should set `alias_scope_used=true` when `game_instance_id` was used instead of `region_id`.
- Grafana and Logging & Admin should group alias-scoped backup activity separately from canonical region-scoped activity so operators can distinguish “legacy scope still in use” from “missing labels due to broken instrumentation”.
- Severity routing for pause-budget alerts is based on whether the paused scope blocks live player command processing:
  - Player-facing scopes use `P0`.
  - Non-player-facing or isolated maintenance scopes use `P1`.
  - Example non-player-facing maintenance scopes include restore rehearsals in quarantined staging, offline verification jobs that pause an isolated admin-only environment, or other backup drills against traffic-detached environments where no live player command path is available.
  - Environment overlays may route both severities to the same operator in a single-admin deployment, but they must preserve the severity distinction in labels and docs.

Terminology note:

- `player-facing` means the environment can currently accept live player traffic or is being prepared to do so during a normal open-traffic deployment.
- `quarantined` means the environment may otherwise be player-facing by class (`staging`, `production`, or `hobby-self-hosted`) but is technically prevented from serving external player traffic during restore, drill, or detached maintenance.
- Quarantined staging or production work should be treated as non-player-facing for pause-budget severity routing and rehearsal safety checks until quarantine is removed.

---

## Post-Restore Secret Hardening

Restoring a player-facing environment (`hobby-self-hosted`, `staging`, or `production`) from backup recreates Kubernetes Secrets and ConfigMaps as they existed at the time of the snapshot. To avoid bringing back stale or compromised credentials, operators must rotate critical secrets immediately after a restore.

Restore isolation is mandatory. Before restoring manifests or PostgreSQL into a player-facing environment, operators must place the environment in a quarantine state so snapshot-era trust material cannot serve real traffic during recovery:

- scale the public Gateway and TCP Proxy workloads to zero, or detach their external `LoadBalancer` / Ingress exposure,
- prevent DNS or traffic-manager cutover to the restored environment,
- keep quarantine in place until post-restore hardening, external credential validation, required sanitization evidence, and smoke checks all succeed.

It is not sufficient to rely on “operators will not send traffic yet” as a procedural control. The restored environment must be technically unable to accept player-facing traffic until the hardening sequence is complete.
A quarantined environment may still be player-facing by environment class, but it is operationally treated as non-player-facing until quarantine is lifted and traffic-open gates pass.

Post-restore hardening is performed by a dedicated Kubernetes Job (for example `post-restore-secret-hardening`) that coordinates multiple flows:

1. JWT signing key and JWKS rotation:
   - Creates or runs a restore-hardening JWT rotation Job (restore mode) derived from `jwt-rotation` but with compromise-style key cutover semantics.
   - Restore mode must remove restored keys from active trust material (`jwks.json`) rather than retaining overlap from the snapshot-era keyset.
   - Waits for the Job to succeed so a fresh JWT signing key is written to the `jwt-signing-keys` Secret and `jwks.json` is regenerated with only uncompromised keys.
   - Verifies that the Account Service is healthy and that services can still validate tokens via the JWKS endpoint.
   - Advances revocation watermark scope and verifies validator convergence before player traffic reopens.
   - See `design/architecture/system-architecture-security.md#jwt-key--jwks-rotation-workflow` for the rotation behavior and this document’s canonical recovery-record section for the required reopen evidence.

2. Database credential rotation:
   - Runs a `db-credential-rotation` Job with a dedicated service account (for example `sa-db-rotation`) that:
     - Reads the current application database credentials from a Secret such as `postgres-credentials`.
     - Uses an admin credential (for example from `postgres-admin-credentials`) to execute `ALTER ROLE <app_user> WITH PASSWORD '<new password>';` against PostgreSQL.
     - Updates the `postgres-credentials` Secret with the new password.
     - Triggers a rolling restart of Deployments and StatefulSets that consume this Secret (for example via `kubectl rollout restart` using the Kubernetes API) so all services reconnect using the new credentials.
   - The Job fails fast on any error so operators can investigate before exposing the restored environment to players.

3. Certificate reissuance (leaf trust reset):
   - Reissue workload mTLS certificates consumed by `FIREMUD_GRPC_*` paths so restored snapshot-era leaf certificates are not trusted after reopen.
   - Reissue the TCP Proxy → Gateway WebSocket mTLS credentials consumed by `FIREMUD_GATEWAY_WS_CLIENT_*` and the Gateway mTLS listener certificate if it was restored from snapshot-era Secrets.
   - Reissue operator client certificates used for internal-only control-plane access.
   - Default restore hardening rotates leaf certificates only; rotating the cluster CA or issuer root is reserved for CA-compromise response and is not part of the standard restore path.
   - Require peer/validator convergence evidence before traffic reopen.
   - See `design/architecture/system-architecture-security.md#key-and-certificate-rotation` and `design/architecture/system-architecture-security.md#tls-termination-for-gateway` for the trust model; recovery evidence is recorded in the canonical recovery record defined here.

4. External credentials (environment-specific secrets):
   - Restoring a namespace also restores any third-party credentials stored as Kubernetes Secrets (for example S3/MinIO access keys for backups and asset storage, SMTP credentials, webhook/API keys, and operator-only client certificates).
   - Post-restore hardening must either rotate/re-issue these credentials or re-bind the restored cluster to the correct per-environment secrets before any external traffic is allowed.
   - At minimum:
     - Ensure object storage credentials used for `pg_dump` uploads and Velero point to the intended bucket and are not stale.
     - Ensure asset store credentials (see `design/architecture/system-architecture-asset-store-runbook.md`) are correct for the environment and rotated if compromise is suspected.
     - Ensure any outbound email/notification credentials are correct for the environment (staging should not be able to send production email).
   - Canonical external validation matrix:
     - Backup storage (`backup-storage`): verify bucket/endpoint binding, access succeeds, and non-production isolation constraints are met.
     - Asset storage (`asset-storage`): verify bucket/endpoint binding and publishing target isolation.
     - Outbound communications (`outbound-comms`): verify SMTP/webhook targets and enforce “staging cannot send production messages” isolation.
     - Operator credentials (`operator-credentials`): verify the expected operator certificate or credential binding for the restored environment.
   - Each class must produce one machine-checkable validation result, one explicit environment-isolation assertion, and one immutable evidence reference in the recovery record before reopening traffic.
   - The recovery record must also include:
     - `validationMethod` for each credential class,
     - `validatedAt`,
     - `validatedBy`,
     - `observedValue` or fingerprint/token target sufficient to prove what was checked without disclosing the secret itself.
   - `observedValue` must be redacted-by-design: include only bucket names, endpoint hosts, certificate fingerprints, binding identifiers, or other non-secret targets. Never store secret values, passwords, private keys, bearer tokens, or full connection strings containing credentials.
   - If these credentials are rotated out-of-band (for example via the cloud provider console), record the required re-bind/re-issue steps in the relevant runbooks so the restore procedure remains repeatable.
   - Before enabling external traffic, run a mandatory external credential validation pass and fail the restore if validation does not pass:
     - `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>`
     - Provide expected values via environment variables such as `EXPECTED_PG_DUMP_BUCKET`, `EXPECTED_ASSET_STORE_BUCKET`, `EXPECTED_ASSET_STORE_ENDPOINT`, `EXPECTED_SMTP_HOST`, and `EXTERNAL_CREDENTIAL_EVIDENCE_REF`, and optionally `PRODUCTION_PG_DUMP_BUCKET` / `PRODUCTION_ASSET_STORE_BUCKET` when validating staging isolation.
   - For staging restores sourced from production-origin snapshots, run mandatory data sanitization and record evidence before traffic reopen:
     - `design/operations/deployments/staging/recovery/<recovery-ref>.json`
     - Export `SANITIZATION_EVIDENCE_REF` with that in-repo evidence path so `validate-external-credentials.sh staging` can enforce the gate.

### Post-Restore Coordination Recovery Gate

After PostgreSQL is restored, but before quarantine is lifted, the restore workflow must prove that Coordination Redis is operating in exactly one approved recovery mode:

1. `cold_start_restore`
   - Verify the target Coordination Redis keyspace is empty for coordination prefixes because the recovery intentionally started from an empty coordination store.
   - Complete the same pause/status validation, post-restore hardening, external credential validation, and smoke gate required for scoped reset recovery before traffic reopen.
   - Record evidence that reset-sensitive session/auth state was dropped or re-established under the restored environment.
2. `scoped_reset_restore`
   - Run the authoritative reset handshake for the affected scope from `system-architecture-redis-reset-and-recovery.md`.
   - Record evidence for:
     - pause completion,
     - `region_epoch` bump performed by the canonical reset control-plane operation,
     - scoped reset execution,
     - ledger reconcile / command convergence,
     - metadata reinitialization,
     - post-reset smoke-check success.
   - Record whether reset-sensitive session/auth prefixes were invalidated as part of the recovery event.

Recovery automation must fail closed if the restore event cannot be classified into one of these two modes with evidence.

## Canonical Recovery Record

Every player-facing restore must produce one canonical recovery record before quarantine is lifted:

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required fields:

- `schemaVersion` (`recovery-record/v1` for the current canonical shape)
- `environment`
- `recoveryRef`
- `restoreSource` (backup/snapshot identifier)
- `coordinationRecoveryMode` (`cold_start_restore` or `scoped_reset_restore`)
- `quarantineStartedAt`
- `quarantineReleasedAt`
- `restoredAt`
- `restoredBy`
- `preflightReportPath` when a traffic-reopen preflight gate ran for the same event
- `expectedBindingsRef`
- `coordinationRecoveryEvidence`
- `sessionRecovery`
- `jwtHardening`
- `databaseCredentialRotation`
- `certificateReissuance`
- `externalCredentialValidation`
- `sanitizationEvidenceRef` when staging is restored from production-origin data
- `smokeStatus`
- `smokeEvidence`
- `reopenApprovedBy`

Nested control-group requirements:

- `jwtHardening` must include rotation job reference, resulting key IDs, revocation watermark evidence, and validator-convergence evidence.
- `databaseCredentialRotation` must include rotation job reference, affected Secret refs, and rollout-restart completion evidence.
- `certificateReissuance` must include workload, bridge, and operator leaf identity evidence plus peer-convergence evidence.
- `externalCredentialValidation` must include one result per credential class (`backup-storage`, `asset-storage`, `outbound-comms`, `operator-credentials`) with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue` or fingerprint, environment-isolation assertion, and immutable evidence reference.
- `coordinationRecoveryEvidence` must prove exactly one restore mode:
  - `cold_start_restore`: empty-coordination proof and cold-start validation evidence.
  - `scoped_reset_restore`: pause/epoch-bump/reset/reconcile/init-meta/smoke evidence for the chosen scope.
- `sessionRecovery` must make reset-sensitive session/auth handling machine-checkable for the restore event, including:
  - `status`
  - `gameSessionHandling` (`preserved`, `invalidated`, `reestablished`)
  - `authSessionHandling` (`preserved`, `invalidated`, `reissued`)
  - `policy` or `reason`
  - `evidenceRefs`
  This field is required for both `cold_start_restore` and `scoped_reset_restore`; restore evidence must not bury session/auth handling only in generic free-form references.

Operator credential evidence representation:

- When the expected binding is a platform resource identifier, store that identifier in `observedValue` (for example `cert-manager://firemud/prod-operator-client`) and treat it as the canonical comparison target.
- When the expected binding is a certificate or key fingerprint, store the fingerprint as `observedValue` and compare it directly to the expected manifest value.
- Do not store both as competing canonical values in one result unless one is clearly marked as supporting detail; recovery validation should answer from the same representation that `expected-bindings.yaml` declares.

Validation rules:

- Quarantine must remain in place until the record is complete and all required control groups show `pass`.
- `quarantineReleasedAt` must be later than the completion times for hardening, external credential validation, and smoke checks.
- Traffic reopen is non-compliant if this record is missing, incomplete, or inconsistent with the restore event.

Illustrative recovery record shape:

```json
{
  "schemaVersion": "recovery-record/v1",
  "environment": "staging",
  "recoveryRef": "2026-03-13-drill-01",
  "restoreSource": "pgdump-2026-03-13T03:00:00Z",
  "coordinationRecoveryMode": "scoped_reset_restore",
  "quarantineStartedAt": "2026-03-13T04:00:00Z",
  "quarantineReleasedAt": "2026-03-13T05:12:00Z",
  "restoredAt": "2026-03-13T04:21:00Z",
  "restoredBy": "operator@example",
  "preflightReportPath": "design/operations/deployments/staging/preflight/2026-03-13-drill-01.json",
  "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
  "coordinationRecoveryEvidence": {
    "status": "pass",
    "mode": "scoped_reset_restore",
    "evidenceRefs": [
      "coordination-pause-complete",
      "region-epoch-bump-complete",
      "coordination-reset-complete",
      "ledger-reconcile-complete",
      "post-reset-smoke-complete"
    ]
  },
  "sessionRecovery": {
    "status": "pass",
    "gameSessionHandling": "preserved",
    "authSessionHandling": "reissued",
    "policy": "tenant-scoped reset preserved resumable gameplay sessions while forcing fresh auth-token issuance",
    "evidenceRefs": ["session-rebind-check-01", "auth-token-reissue-check-01"]
  },
  "jwtHardening": {
    "status": "pass",
    "jobRef": "job/post-restore-secret-hardening-jwt",
    "keyIds": ["staging-2026-03-13-a"],
    "validatorConvergenceEvidence": ["jwks-cache-refresh-complete"]
  },
  "databaseCredentialRotation": {
    "status": "pass",
    "jobRef": "job/db-credential-rotation",
    "affectedSecrets": ["postgres-credentials"],
    "rolloutEvidence": ["deployment-restart-complete"]
  },
  "certificateReissuance": {
    "status": "pass",
    "evidenceRefs": ["workload-leaf-reissued", "bridge-leaf-reissued", "operator-leaf-reissued"]
  },
  "externalCredentialValidation": {
    "status": "pass",
    "results": [
      {
        "credentialClass": "backup-storage",
        "validationMethod": "bucket-bind-check",
        "validatedAt": "2026-03-13T05:00:00Z",
        "validatedBy": "operator@example",
        "observedValue": "firemud-staging-backups",
        "isolationAssertion": "not production bucket",
        "evidenceRef": "ext-backup-check-01"
      },
      {
        "credentialClass": "asset-storage",
        "validationMethod": "bucket-bind-check",
        "validatedAt": "2026-03-13T05:01:00Z",
        "validatedBy": "operator@example",
        "observedValue": "firemud-staging-assets",
        "isolationAssertion": "not production bucket",
        "evidenceRef": "ext-asset-check-01"
      },
      {
        "credentialClass": "outbound-comms",
        "validationMethod": "endpoint-bind-check",
        "validatedAt": "2026-03-13T05:02:00Z",
        "validatedBy": "operator@example",
        "observedValue": "smtp.staging.firemud.internal",
        "isolationAssertion": "not production mail relay",
        "evidenceRef": "ext-outbound-check-01"
      },
      {
        "credentialClass": "operator-credentials",
        "validationMethod": "binding-check",
        "validatedAt": "2026-03-13T05:03:00Z",
        "validatedBy": "operator@example",
        "observedValue": "cert-manager://firemud/staging-operator-client",
        "isolationAssertion": "bound to staging operator identity",
        "evidenceRef": "ext-operator-check-01"
      }
    ]
  },
  "sanitizationEvidenceRef": "design/operations/deployments/staging/recovery/2026-03-13-drill-01-sanitization.json",
  "smokeStatus": "pass",
  "smokeEvidence": ["design/operations/deployments/staging/smoke/2026-03-13-drill-01.json"],
  "reopenApprovedBy": "operator@example"
}
```

Illustrative `cold_start_restore` recovery record variant:

```json
{
  "schemaVersion": "recovery-record/v1",
  "environment": "staging",
  "recoveryRef": "2026-03-14-drill-02",
  "restoreSource": "pgdump-2026-03-14T03:00:00Z",
  "coordinationRecoveryMode": "cold_start_restore",
  "quarantineStartedAt": "2026-03-14T04:00:00Z",
  "quarantineReleasedAt": "2026-03-14T05:05:00Z",
  "restoredAt": "2026-03-14T04:18:00Z",
  "restoredBy": "operator@example",
  "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
  "coordinationRecoveryEvidence": {
    "status": "pass",
    "mode": "cold_start_restore",
    "evidenceRefs": [
      "coordination-keyspace-empty",
      "cold-start-validation-complete",
      "sessions-reestablished-after-reset"
    ]
  },
  "sessionRecovery": {
    "status": "pass",
    "gameSessionHandling": "reestablished",
    "authSessionHandling": "reissued",
    "policy": "cold-start restore dropped old coordination-backed sessions and required fresh login/token issuance before reopen",
    "evidenceRefs": ["game-session-reestablished-check-01", "auth-token-reissue-check-02"]
  },
  "jwtHardening": {
    "status": "pass",
    "jobRef": "job/post-restore-secret-hardening-jwt",
    "keyIds": ["staging-2026-03-14-a"]
  },
  "databaseCredentialRotation": {
    "status": "pass",
    "jobRef": "job/db-credential-rotation"
  },
  "certificateReissuance": {
    "status": "pass",
    "evidenceRefs": ["workload-leaf-reissued"]
  },
  "externalCredentialValidation": {
    "status": "pass",
    "results": [
      {
        "credentialClass": "backup-storage",
        "validationMethod": "bucket-bind-check",
        "validatedAt": "2026-03-14T05:00:00Z",
        "validatedBy": "operator@example",
        "observedValue": "firemud-staging-backups",
        "isolationAssertion": "not production bucket",
        "evidenceRef": "ext-backup-check-02"
      },
      {
        "credentialClass": "asset-storage",
        "validationMethod": "bucket-bind-check",
        "validatedAt": "2026-03-14T05:01:00Z",
        "validatedBy": "operator@example",
        "observedValue": "firemud-staging-assets",
        "isolationAssertion": "not production bucket",
        "evidenceRef": "ext-asset-check-02"
      },
      {
        "credentialClass": "outbound-comms",
        "validationMethod": "endpoint-bind-check",
        "validatedAt": "2026-03-14T05:02:00Z",
        "validatedBy": "operator@example",
        "observedValue": "smtp.staging.firemud.internal",
        "isolationAssertion": "not production mail relay",
        "evidenceRef": "ext-outbound-check-02"
      },
      {
        "credentialClass": "operator-credentials",
        "validationMethod": "binding-check",
        "validatedAt": "2026-03-14T05:03:00Z",
        "validatedBy": "operator@example",
        "observedValue": "cert-manager://firemud/staging-operator-client",
        "isolationAssertion": "bound to staging operator identity",
        "evidenceRef": "ext-operator-check-02"
      }
    ]
  },
  "smokeStatus": "pass",
  "smokeEvidence": ["design/operations/deployments/staging/smoke/2026-03-14-drill-02.json"],
  "reopenApprovedBy": "operator@example"
}
```

The `post-restore-secret-hardening` Job runs after PostgreSQL and core services have been restored and basic health checks pass, but **before** the restored environment is considered player-facing. It uses least-privilege service accounts:

- JWT rotation service accounts can only read/update the `jwt-signing-keys` Secret, the `jwt-jwks` Secret for player-facing environments (`hobby-self-hosted`, staging, production), and, optionally, the Account Service Deployment. Non-player-facing environments may use a JWKS ConfigMap.
- Database rotation service accounts can only read/update the PostgreSQL credential Secrets and, optionally, restart the Deployments/StatefulSets that use them.
- Certificate reissuance automation can only read/update the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

Runbooks should treat this Job (or equivalent operator automation for hobby/self-hosted) as a mandatory step in any player-facing disaster recovery:

1. Enter restore quarantine by removing or disabling external traffic paths to Gateway and TCP Proxy.
2. Restore PostgreSQL and Kubernetes manifests as described above.
3. Run `post-restore-secret-hardening` in the target namespace and wait for it to complete successfully.
4. Confirm workload, bridge, and operator leaf certificates have been reissued and peers have converged on the new identities.
5. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values and ensure it succeeds.
6. For staging restores from production-origin data, ensure data sanitization evidence exists and is referenced by `SANITIZATION_EVIDENCE_REF`.
7. Confirm application health checks, login/session flows, and JWT validation.
8. Only then remove quarantine and route external or player traffic to the restored cluster.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run an equivalent one-shot restore-hardening automation that performs the same four control groups (JWT/JWKS rotation, DB credential rotation, certificate reissuance, external credential validation) and writes the canonical recovery record to `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json` before reopening player traffic.

---

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as part of routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` Job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy (see the backup verification section).
2. Schedule a low-traffic window and notify stakeholders of the planned rotation.
3. Run the `db-credential-rotation` Job in the target namespace so it:
   - Generates a new password for the application role(s) in PostgreSQL using admin credentials from `postgres-admin-credentials`.
   - Updates the `postgres-credentials` Secret with the new password.
   - Triggers a rolling restart of Deployments and StatefulSets that consume this Secret so services reconnect with the new credentials.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat the rotation later with additional diagnostics.

No CronJob is defined for automatic DB credential rotation; any such cadence should be explicitly chosen based on compliance requirements and operational experience.

## Production Backup Readiness Evidence

Production releases classified as `roll-forward-only` must include fresh backup-readiness evidence stored at:

- `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`

Required fields:

- `environment` (`production`)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `rollbackMode` (`roll-forward-only`)
- `promotionAttestationRef`
- `serviceDigests`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `restorePlanRef`
- `restoreRecoveryRecordRef`
- `coordinatedBackupScope`
- `evidenceRefs[]`

Freshness policy:

- `backupLastSuccessAt` must be within 90 minutes of production preflight.
- `backupVerifyLastSuccessAt` must be within 36 hours of production preflight.
- `restoreDrillLastSuccessAt` should be within the last 30 days unless an explicit break-glass waiver is recorded.

Validation rules:

- `promotionAttestationRef` and `serviceDigests` must match the attestation being promoted.
- `restoreRecoveryRecordRef` must point to a canonical recovery record from a drill that completed quarantine, post-restore hardening, external credential validation, and smoke verification for the relevant environment class.
- `coordinatedBackupScope` must be `tenant_id + region_id` for player-facing production release readiness. Alias-scoped evidence is valid only for quarantined drills and must not satisfy `roll-forward-only` production promotion.
- Production CI and preflight must fail when any of those checks do not pass.

This evidence is consumed by production CI and preflight and is mandatory before applying a `roll-forward-only` production release.

## Production Traffic-Open Backup Evidence

Before opening production to player traffic for the first time, or reopening it after a restore into a fresh environment boundary, operators must record proof that the backup pipeline is already functioning for that environment.

This is a specialized `backup-readiness` artifact used for production traffic-open gating, not a second unrelated evidence family. It lives under the same `design/operations/deployments/production/backup-readiness/` namespace as release-time backup evidence, but uses the `first-live-<deployment-ref>.json` naming pattern so tooling can distinguish “traffic-open readiness” from “roll-forward-only release readiness” without introducing a separate schema lineage.
This artifact is the canonical production traffic-open gate evidence referenced by the deployment runbook.

Canonical evidence path:

- `design/operations/deployments/production/backup-readiness/first-live-<deployment-ref>.json`

Required fields:

- `environment` (`production`)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupStorageBinding`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `restoreRecoveryRecordRef`
- `coordinatedBackupScope`
- `evidenceRefs[]`

Validation rules:

- `backupLastSuccessAt` must point to a successful logical backup upload produced against the live production environment binding.
- `backupVerifyLastSuccessAt` must point to a successful verification run against that same environment binding.
- `restoreDrillLastSuccessAt` must point to a successful restore drill against that same environment class/binding and must be within 30 days of the traffic-open preflight.
- `restoreRecoveryRecordRef` must point to a canonical recovery record from a drill that completed quarantine, post-restore hardening, external credential validation, and smoke verification for the relevant environment class.
- `coordinatedBackupScope` must be `tenant_id + region_id` for player-facing production traffic-open readiness. Alias-scoped evidence is valid only for quarantined drills and must not satisfy production traffic-open gates.
- Production traffic-open preflight must fail when this evidence is missing, stale, or bound to the wrong bucket/endpoint.
- The canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`.

Illustrative production traffic-open backup-readiness record:

```json
{
  "environment": "production",
  "deploymentRef": "2026-03-13-prod-first-live-01",
  "assessedAt": "2026-03-13T08:45:00Z",
  "assessedBy": "operator@example",
  "backupStorageBinding": "firemud-production-backups",
  "backupLastSuccessAt": "2026-03-13T08:10:00Z",
  "backupVerifyLastSuccessAt": "2026-03-13T08:25:00Z",
  "restoreDrillLastSuccessAt": "2026-03-05T09:00:00Z",
  "restoreRecoveryRecordRef": "design/operations/deployments/production/recovery/2026-03-05-drill-01.json",
  "coordinatedBackupScope": "tenant_id + region_id",
  "evidenceRefs": [
    "pgdump-upload-2026-03-13T08:10:00Z",
    "backup-verify-2026-03-13T08:25:00Z",
    "restore-drill-2026-03-05T09:00:00Z"
  ]
}
```

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml` with:

- `lastSuccessfulBackupAt`
- `lastSuccessfulRestoreDrillAt` – timestamp of the most recent restore drill that passed the required restore-proof gates for hobby/self-hosted reopen readiness.
- `lastRestoreDrillAt` – timestamp of the most recent restore drill attempt, whether it passed or failed.
- `retentionDailyPoints`
- `backupTooling`
- `evidenceRefs[]`

Restore hardening for hobby/self-hosted must fail closed for player-traffic reopen if this record is missing, stale, or below baseline (`>=1` backup/24h, `>=7` daily retention points, `>=1` restore drill/30d).

## Hobby Traffic-Open Evidence

Before opening `hobby-self-hosted` to player traffic for the first time, or reopening it after a restore, operators must record traffic-open evidence at:

- `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json`

Required fields:

- `schemaVersion` (`traffic-open-record/v1` for the current canonical shape)
- `environment` (`hobby-self-hosted`)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `preflightReportPath`
- `evidenceRefs[]`

Validation rules:

- `backupComplianceRef` must point to a current `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml` record that satisfies the minimum baseline.
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass` for first-live and reopen events.
- Hobby player traffic must not open when this evidence is missing, stale, or bound to a failed preflight run.
- The traffic-open artifact should be written or refreshed for each first-live or reopen event, even when the referenced backup-compliance record itself has not changed, so the evidence remains bound to the current deployment/reopen lineage.

Illustrative hobby traffic-open record:

```json
{
  "schemaVersion": "traffic-open-record/v1",
  "environment": "hobby-self-hosted",
  "deploymentRef": "2026-03-13-home-cluster-01",
  "assessedAt": "2026-03-13T09:15:00Z",
  "assessedBy": "operator@example",
  "backupComplianceRef": "design/operations/deployments/hobby-self-hosted/backup-compliance.yaml",
  "preflightReportPath": "design/operations/deployments/hobby-self-hosted/preflight/2026-03-13-home-cluster-01.json",
  "evidenceRefs": [
    "daily-backup-present",
    "restore-drill-within-30d"
  ]
}
```

Naming rule:

- `<deployment-ref>` and `<recovery-ref>` should use lowercase ASCII plus digits and `-`, and should remain stable for the single deployment or recovery event they represent.
- `<recovery-ref>` should be normalized the same way as `<deployment-ref>` so restore tooling, recovery evidence, and follow-up incident references all point at one stable token for the same recovery event.

---

## Restore Workflow Summary

| Environment | Steps |
| --- | --- |
| **Kubernetes** | Restore PostgreSQL from `pg_dump` -> choose and record `cold_start_restore` or `scoped_reset_restore` -> restore other resources with Velero -> run the coordination recovery gate for the chosen mode -> run post-restore hardening and smoke checks -> reopen traffic only after recovery evidence is complete |
| **Docker Compose** | `dev-tools/restores/restore-db.sh` snapshot -> choose and record `cold_start_restore` or `scoped_reset_restore` -> restart containers only after the chosen coordination recovery path is understood -> follow cold-start/reset behavior and local validation before reopening traffic |

Redis always uses AOF for crash recovery during runtime but is **never** restored from backup images. If Coordination Redis starts empty, treat it as a reset/cold start scenario as described in the Redis architecture docs.

For **diagnostic purposes**, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into **isolated, throwaway Redis instances** to inspect keys and coordination history during incident analysis. These diagnostic snapshots are strictly read-only tools:

- They must **not** be restored into live Coordination Redis clusters or used to overwrite existing AOF/volumes.
- They are never treated as rollback images for gameplay; recovery for player-visible environments always follows the pattern above (restore PostgreSQL, restart services so Redis recovers via AOF or starts empty, and, when needed, apply the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model)). Severe logical bugs that corrupt coordination state are remediated via scoped coordination resets, not by rolling Redis back to older snapshots.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
