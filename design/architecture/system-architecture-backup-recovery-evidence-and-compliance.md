# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Implementation Notes

Backup-pause metrics are maintenance/reset observability, not player-facing PostgreSQL-recovery proof. They may retain bounded `region`/`tenant` scope labels and alias-usage migration signals for those maintenance workflows, but accepted recovery readiness is proved by one environment-wide recovery-controller lineage. Exact tenant and region identities belong in retained maintenance evidence and control-plane reads, not in the accepted backup-recovery gate.

## Backup Observability and Alerts

Backup and verification jobs must emit simple, environment-agnostic metrics:

- `backup_last_success_timestamp_seconds`
- `backup_verify_last_success_timestamp_seconds`
- `backup_restore_drill_last_success_timestamp_seconds`
- `backup_restore_drill_total{result,mode}`
- `backup_tick_pause_wait_seconds{scope_type,scope}` (maintenance/reset only)
- `backup_tick_pause_duration_seconds{scope_type,scope}` (maintenance/reset only)
- `backup_tick_pause_wait_budget_seconds{scope_type,scope}` (maintenance/reset only)
- `backup_tick_pause_duration_budget_seconds{scope_type,scope}` (maintenance/reset only)
- `backup_ticks_paused{scope_type,scope}` (maintenance/reset only)
- `backup_commands_queued_during_pause_total{scope_type,scope}` (maintenance/reset only)
- `backup_pause_attempts_total{scope_type,result}`
- `backup_pause_scope_alias_requests_total{alias="game_instance_id"}`
- optional `backup_run_total{result}` and `backup_verify_run_total{result}`

Prometheus should also publish derived breach indicators:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_tick_pause_wait_budget_breached{scope_type,scope}`
- `backup_tick_pause_duration_budget_breached{scope_type,scope}`
- `backup_ticks_paused_budget_breached{scope_type,scope}`

Alerting policy:

- missed backups are `P1`
- missed verification is `P1` or `P2` depending on environment class
- player-facing pause-budget breaches are `P0`
- stale restore-drill proof is `P1`

The canonical backup/recovery severity matrix lives in `system-architecture-backup-recovery.md`. This document and Grafana alert snippets must mirror that matrix rather than redefining severities independently.

Prometheus and Alertmanager should also carry clear `service`, `severity`, `owner`, and `runbook` annotations on these alerts, and Grafana should include a dedicated Backups section or dashboard that visualizes freshness, restore-proof age, recent backup/verify success vs failure, restore-drill results by recovery mode, and pause-safety signals.

Backup pause metrics and dashboards should use canonical `scope_type` and `scope` labels for maintenance/reset pause-budget signals. Those breaches do not establish PostgreSQL-recovery readiness; quarantined or drill-only scopes may downgrade severity according to environment policy.

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
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `evidenceRefs[]`

Freshness policy:

- `backupLastSuccessAt` within 90 minutes of production preflight
- `backupVerifyLastSuccessAt` within 36 hours
- `restoreDrillLastSuccessAt` within 30 days unless explicit break-glass waiver is recorded

Validation rules:

- evidence must match the promoted attestation
- `restoreRecoveryRecordRef` must point to the post-finalization immutable recovery projection from a drill that completed quarantine, post-restore hardening, external credential validation, and smoke verification
- `recoveryControllerLineage` must dereference the finalized environment-wide `cold_start_restore` controller state and its immutable backup, restore-tool, participant, hardening, confidentiality, and smoke evidence; tenant/region backup-pause proof is not required or sufficient
- `backupConfidentialityEvidence` must prove encrypted transport and storage, environment-scoped least-privilege access and audit, retention/secure deletion, and production-origin drill quarantine, sanitization, validation, and deletion when applicable

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
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `evidenceRefs[]`

Validation rules:

- backup, verification, and restore-drill evidence must all bind to the live production environment
- `restoreDrillLastSuccessAt` must be within 30 days
- `recoveryControllerLineage` must identify the durable environment-wide recovery-controller state for the current recovery boundary. Before release, preflight validates that durable state at `ready_to_reopen`; the checked-in traffic-open JSON is exported only after the controller reaches `finalized` and is not a prerequisite for that same release.
- `backupConfidentialityEvidence` must prove the backup confidentiality invariant and any required production-origin non-production sanitization/deletion evidence
- the canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml`

Required fields:

- `schemaVersion`
- `environment`
- `status` (`pass`)
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
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; the controller may hold a runtime authorization before release)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` when `eventType=reopen`
- `preflightReportPath`
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `evidenceRefs[]`

Validation rules:

- `backupComplianceRef` must point to a current compliant record
- `baselineRecoveryRecordRef` must point to a finalized exported projection of an isolated drill proving the environment-wide `cold_start_restore` contract for the player-facing hobby boundary; a reopen event must additionally reference the durable actual-recovery controller for that restore
- a reopen actual-recovery controller must be `ready_to_reopen` when preflight authorizes the event; its idempotent release reconciliation must apply and observe quarantine release and reach `finalized` before traffic flows, after which the exporter writes both checked-in projections
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- `PREFLIGHT-BACKUP-003` must reject a missing or stale projection and any deployment, event, baseline-recovery, or actual-recovery lineage that does not match the current traffic-open event
- hobby player traffic must not open when this evidence is missing, stale, mismatched, or bound to a failed preflight run
- the traffic-open projection must be exported or refreshed after the controller finalizes every first-live or reopen event, even when the referenced compliance record did not change, so the retained projection remains bound to the current finalized deployment or recovery lineage and cannot be reused for a later event

## Canonical Recovery Record

Every player-facing restore must establish one durable environment-wide recovery-controller record and reach `ready_to_reopen` before quarantine can be released. After the controller reaches `finalized`, the workflow exports one canonical checked-in recovery JSON projection for audit and later evidence reuse; that projection is not part of the same release transaction.

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
- `recoveryControllerLineage`
- `expectedBindingsRef`
- `coordinationRecoveryEvidence`
- `backupConfidentialityEvidence`
- `sessionRecovery`
- `jwtHardening`
- `databaseCredentialRotation`
- `certificateReissuance`
- `externalCredentialValidation`
- `secretComplianceRefresh`
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
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed, and whether each class used `lastProvisionedAt` or `lastRotationAt`
- `recoveryControllerLineage` identifies the durable controller state, environment-wide scope, linked artifact and participant lineage, pre-release `ready_to_reopen` approval, and post-release `finalized` state when this projection is exported
- `coordinationRecoveryEvidence` proves player-facing `cold_start_restore`; `scoped_reset_restore` with surviving Redis is deferred and quarantined and cannot satisfy this field for traffic reopen
- `backupConfidentialityEvidence` proves encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and production-origin non-production quarantine, sanitization, validation, and deletion when applicable
- `sessionRecovery` makes reset-sensitive session/auth handling machine-checkable and must use explicit handling values:
  - `gameSessionHandling`: `preserved`, `invalidated`, or `reestablished`
  - `authSessionHandling`: `preserved`, `invalidated`, or `reissued`

Validation rules:

- quarantine remains in place until the durable controller reaches `ready_to_reopen` and all required control groups pass
- the checked-in projection may be absent during the release; after finalization, its `quarantineReleasedAt` must be later than restore-safe-mode entry, coordination recovery, hardening, external-credential validation, secret-compliance refresh, and smoke-check completion times
- traffic reopen is non-compliant if the durable controller state is missing, incomplete, or inconsistent with the restore event; a missing or mutable post-finalization projection is a later evidence-integrity failure, not a reason to create a circular pre-release dependency

Operator credential evidence representation:

- when the expected binding is a platform resource identifier, store that identifier in `observedValue`
- when the expected binding is a certificate or key fingerprint, store that fingerprint in `observedValue`
- do not store competing canonical representations in one result unless one is clearly marked as supporting detail

Illustrative player-facing recovery-record examples must follow the canonical environment-wide `cold_start_restore` shape. A `scoped_reset_restore` example is permitted only for an explicitly quarantined experiment and must not be reused as readiness evidence.

## Naming Rule

- `<deployment-ref>` and `<recovery-ref>` use lowercase ASCII plus digits and `-`
- each token remains stable for the single deployment or recovery event it represents
