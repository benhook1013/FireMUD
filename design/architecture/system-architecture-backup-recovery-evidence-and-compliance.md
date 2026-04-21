# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Backup Observability and Alerts

Backup and verification jobs must emit simple, environment-agnostic metrics:

- `backup_last_success_timestamp_seconds`
- `backup_verify_last_success_timestamp_seconds`
- `backup_restore_drill_last_success_timestamp_seconds`
- `backup_restore_drill_total{result,mode}`
- `backup_tick_pause_wait_seconds{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_duration_seconds{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_wait_budget_seconds{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_duration_budget_seconds{scope_type,tenantId?,regionId?}`
- `backup_ticks_paused{scope_type,tenantId?,regionId?}`
- `backup_commands_queued_during_pause_total{scope_type,tenantId?,regionId?}`
- `backup_pause_attempts_total{scope_type,result}`
- `backup_pause_scope_alias_requests_total{alias="game_instance_id"}`
- optional `backup_run_total{result}` and `backup_verify_run_total{result}`

Prometheus should also publish derived breach indicators:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_tick_pause_wait_budget_breached{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_duration_budget_breached{scope_type,tenantId?,regionId?}`
- `backup_ticks_paused_budget_breached{scope_type,tenantId?,regionId?}`

Alerting policy:

- missed backups are `P1`
- missed verification is `P1` or `P2` depending on environment class
- player-facing pause-budget breaches are `P0`
- stale restore-drill proof is `P1`

The canonical backup/recovery severity matrix lives in `system-architecture-backup-recovery.md`. This document and Grafana alert snippets must mirror that matrix rather than redefining severities independently.

Prometheus and Alertmanager should also carry clear `service`, `severity`, `owner`, and `runbook` annotations on these alerts, and Grafana should include a dedicated Backups section or dashboard that visualizes freshness, restore-proof age, recent backup/verify success vs failure, restore-drill results by recovery mode, and pause-safety signals.

Until canonical `region_id` scope is enforced end to end, backup pause metrics and dashboards must treat alias-scope usage as a first-class migration signal rather than hiding it inside generic pause failures. `scope_type` should distinguish canonical scopes such as `region` and `tenant` from alias scopes such as `game_instance_alias`, and player-facing pause-budget breaches should route as `P0` while quarantined or drill-only scopes may downgrade severity according to environment policy.

## Production Backup Readiness Evidence

Production releases classified as `roll-forward-only` must include fresh backup-readiness evidence at:

- `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`

Required fields:

- `environment`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `rollbackMode`
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

- `backupLastSuccessAt` within 90 minutes of production preflight
- `backupVerifyLastSuccessAt` within 36 hours
- `restoreDrillLastSuccessAt` within 30 days unless explicit break-glass waiver is recorded

Validation rules:

- evidence must match the promoted attestation
- `restoreRecoveryRecordRef` must point to a canonical recovery record from a drill that completed quarantine, post-restore hardening, external credential validation, and smoke verification
- `coordinatedBackupScope` must be `tenant_id + region_id` for player-facing production readiness

## Production Traffic-Open Backup Evidence

Before opening production to player traffic for the first time, or reopening it after restore into a fresh environment boundary, operators must record proof that the backup pipeline is already functioning for that environment.

This is a specialized `backup-readiness` artifact used for production traffic-open gating, not a separate evidence family. It lives under the same `design/operations/deployments/production/backup-readiness/` namespace as release-time backup evidence but uses the `first-live-<deployment-ref>.json` naming pattern so tooling can distinguish traffic-open readiness from ordinary roll-forward-only release readiness.

Canonical evidence path:

- `design/operations/deployments/production/backup-readiness/first-live-<deployment-ref>.json`

Required fields:

- `environment`
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

- backup, verification, and restore-drill evidence must all bind to the live production environment
- `restoreDrillLastSuccessAt` must be within 30 days
- `coordinatedBackupScope` must be `tenant_id + region_id`
- the canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml`

Required fields:

- `lastSuccessfulBackupAt`
- `lastSuccessfulRestoreDrillAt`
- `lastRestoreDrillAt`
- `retentionDailyPoints`
- `backupTooling`
- `evidenceRefs[]`

Restore hardening for hobby/self-hosted must fail closed for player-traffic reopen if this record is missing, stale, or below baseline.

## Hobby Traffic-Open Evidence

Before opening `hobby-self-hosted` to player traffic for the first time, or reopening it after a restore, operators must record traffic-open evidence at:

- `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json`

Required fields:

- `schemaVersion`
- `environment`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `preflightReportPath`
- `evidenceRefs[]`

Validation rules:

- `backupComplianceRef` must point to a current compliant record
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- hobby player traffic must not open when this evidence is missing, stale, or bound to a failed preflight run
- the traffic-open artifact should be written or refreshed for each first-live or reopen event even when the referenced compliance record did not change, so the evidence remains bound to the current deployment or recovery lineage

## Canonical Recovery Record

Every player-facing restore must produce one canonical recovery record before quarantine is lifted:

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required top-level fields:

- `schemaVersion`
- `environment`
- `recoveryRef`
- `restoreSource`
- `restoreSafeMode`
- `coordinationRecoveryMode`
- `quarantineStartedAt`
- `quarantineReleasedAt`
- `restoredAt`
- `restoredBy`
- `preflightReportPath` when applicable
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

- `restoreSafeMode` includes evidence that player ingress was disabled, normal background processors and outbound integrations were stopped or restore-safe-fenced, Game Session tick execution and command intake could not create fresh coordination state before the coordination recovery gate, and only approved maintenance Jobs ran before quarantine release
- `jwtHardening` includes rotation job reference, resulting key IDs, revocation watermark evidence, and validator-convergence evidence
- `databaseCredentialRotation` includes rotation job reference, affected Secret refs, and rollout-restart completion evidence
- `certificateReissuance` includes workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation` includes one result per credential class with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue`, isolation assertion, and immutable evidence ref
- `coordinationRecoveryEvidence` proves exactly one restore mode
- `sessionRecovery` makes reset-sensitive session/auth handling machine-checkable and must use explicit handling values:
  - `gameSessionHandling`: `preserved`, `invalidated`, or `reestablished`
  - `authSessionHandling`: `preserved`, `invalidated`, or `reissued`

Validation rules:

- quarantine remains in place until the record is complete and all required control groups pass
- `quarantineReleasedAt` must be later than restore-safe-mode entry, coordination recovery, hardening, external-credential validation, and smoke-check completion times
- traffic reopen is non-compliant if this record is missing, incomplete, or inconsistent with the restore event

Operator credential evidence representation:

- when the expected binding is a platform resource identifier, store that identifier in `observedValue`
- when the expected binding is a certificate or key fingerprint, store that fingerprint in `observedValue`
- do not store competing canonical representations in one result unless one is clearly marked as supporting detail

Illustrative recovery-record examples should continue to follow the canonical `scoped_reset_restore` and `cold_start_restore` shapes from the backup architecture baseline so automation and manual drills produce comparable artifacts across environments.

## Naming Rule

- `<deployment-ref>` and `<recovery-ref>` use lowercase ASCII plus digits and `-`
- each token remains stable for the single deployment or recovery event it represents
