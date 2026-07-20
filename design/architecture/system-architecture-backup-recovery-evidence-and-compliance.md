# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Implementation Notes

Current backup checks prove artifact existence and selected evidence shape, not the accepted environment-wide cold-start recovery boundary. The scheduled dump does not yet emit complete environment/schema/service/tool lineage, preflight does not dereference and validate the required recovery record and participant results, and no automated production-equivalent drill proves backup-under-write through controlled reopen. No resumable recovery controller or crash-recoverable traffic-release state machine exists. Player-facing hosted restore readiness remains blocked.

## Backup Observability and Alerts

Backup and verification jobs must emit simple, environment-agnostic metrics:

- `backup_last_success_timestamp_seconds`
- `backup_verify_last_success_timestamp_seconds`
- `backup_restore_drill_last_success_timestamp_seconds`
- `backup_restore_drill_total{result,mode}`
- `backup_artifact_lineage_valid{environment}`
- `backup_artifact_restore_readable{environment}`
- `recovery_participant_convergence_total{participant,result}`
- `recovery_oldest_unresolved_age_seconds{participant}`
- optional `backup_run_total{result}` and `backup_verify_run_total{result}`

Prometheus should also publish derived breach indicators:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_artifact_lineage_invalid`
- `backup_artifact_restore_unreadable`
- `recovery_participant_convergence_blocked`

Alerting policy:

- missed backups are `P1`
- missed verification is `P1` or `P2` depending on environment class
- an attempted reopen with incomplete cold-start convergence is `P0`
- stale restore-drill proof is `P1`

The canonical backup/recovery severity matrix lives in `system-architecture-backup-recovery.md`. This document and Grafana alert snippets must mirror that matrix rather than redefining severities independently.

Prometheus and Alertmanager should also carry clear `service`, `severity`, `owner`, and `runbook` annotations on these alerts, and Grafana should include a dedicated Backups section or dashboard that visualizes freshness, artifact lineage/readability, restore-proof age, restore-drill results, and recovery-participant convergence.

Maintenance pause metrics remain owned by the region maintenance/reset contracts. Routine backup dashboards must not interpret absence of pause spans or pause metrics as a backup failure.

## Production Backup Readiness Evidence

Production releases classified as `roll-forward-only`, any release whose recovery-compatibility result requires a new drill, and production first-live/reopen baselines must include a full backup-readiness record at:

- `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`

Required fields:

- `environment`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `rollbackMode` (`rollback-compatible` or `roll-forward-only`)
- `promotionAttestationRef`
- `sourceServiceDigests`
- `candidateServiceDigests`
- `candidateMigrationPathRef`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `newestVerifiedRestorablePointAt`
- `restoreDrillLastSuccessAt`
- `restorePlanRef`
- `restoreRecoveryRecordRef`
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `evidenceRefs[]`

Freshness policy:

- `newestVerifiedRestorablePointAt` within the 15-minute hosted RPO objective at production preflight; schedule start or object existence does not satisfy this field
- `backupLastSuccessAt` records pipeline execution separately and cannot substitute for the verified restorable point
- `backupVerifyLastSuccessAt` within 36 hours
- `restoreDrillLastSuccessAt` within 30 days; a waiver may postpone an isolated drill or salvage exercise but cannot authorize player-facing first-live, post-rewind reopen, or `roll-forward-only` promotion without the required proof

Evidence reuse and invalidation:

- rollback-compatible steady-state releases use the compact recovery-compatibility result below and need not duplicate this full record when reuse is valid
- an invalidating recovery-contract change requires a new production-equivalent drill even when the 30-day window has not expired
- a `roll-forward-only` release never reuses only the cadence record; its source artifact comes from the current production database lineage under representative writes and is restored using candidate recovery tooling, exact candidate service digests, the candidate migration path, and candidate config/bindings
- first-live and reopen events require environment-specific proof for the boundary being opened
- scheduled isolated drills and their evidence production are automated; routine success does not depend on an operator transcribing timestamps or result fields

Validation rules:

- evidence must match the promoted attestation
- `restoreRecoveryRecordRef` must point to a finalized `production-equivalent-drill` record with `trafficExposure=isolated-drill` that completed quarantine, post-restore hardening, external credential validation, smoke verification, and the isolated controlled-reopen transition
- the recovery record must prove `cold_start_restore`, empty Coordination Redis, environment-wide session and epoch/fence invalidation, safe durable-participant and external-effect dispositions, and controlled reopen
- `backupCoverage` must be `environment-wide-postgresql`; a tenant/region pair cannot stand in for the whole database
- `backupToolDigest` must match the tool that produced the source artifact and its source lineage; `recoveryToolDigest` and the recovery-contract fingerprint must match the candidate proved by the drill
- `sourceServiceDigests` and the backup artifact lineage identify the snapshot-time production source; `candidateServiceDigests` and `candidateMigrationPathRef` identify the exact recovery candidate proved through controlled reopen

## Production Recovery Compatibility Result

Every production promotion records a small `recoveryCompatibility` object in its promotion attestation or deployment record, or references an equivalent immutable result. It does not copy the full recovery record.

Required fields:

- `baselineRecoveryRecordRef`
- `baselineRecoveryContractFingerprint`
- `candidateRecoveryContractFingerprint`
- `changedDimensions[]`
- `compatibilityStatus` (`compatible`, `drill_required`, or `incompatible`)
- `compatibilityRationale`
- `evaluatedAt`
- `evaluatorToolDigest`
- `newDrillRequired`
- `backupReadinessRef` when `newDrillRequired=true`

The evaluator compares backup/restore tool compatibility, database and migration restore compatibility, durable workflow and reconciliation semantics, Coordination Redis recovery, enabled participant inventory, post-restore hardening, secret/binding contracts, and environment binding. Restore-compatible additive migrations and routine secret-value rotation do not require a new drill when their recovery, authority, delivery, and hardening contracts are unchanged. A semantic or contract change returns `drill_required`; a `roll-forward-only` release always sets `newDrillRequired=true`.

## Production Traffic-Open Backup Evidence

Before opening production to player traffic for the first time, or reopening it after restore into a fresh environment boundary, automated playbooks must record proof that the backup and recovery path functions for that environment. The operator authorizes the protected transition rather than manually constructing routine evidence.

Traffic-open evidence is a separate event-bound wrapper around the canonical recovery and backup-readiness evidence. It uses the existing writer/preflight namespace for both first-live and reopen events.

Canonical evidence path:

- `design/operations/deployments/production/traffic-open/<first-live|reopen>-<deployment-ref>.json`

Required fields:

- `schemaVersion`
- `environment`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`authorized` or `finalized`)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupStorageBinding`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `backupReadinessRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` when `eventType=reopen`
- `backupCoverage`
- `backupArtifactRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `sourceEnvironmentBinding`
- `drillTargetBoundary`
- `trafficExposure` (`isolated-drill` for the referenced drill)
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `evidenceRefs[]`

Validation rules:

- backup and verification evidence must bind to the production source lineage; `backupReadinessRef` and `baselineRecoveryRecordRef` must identify a finalized isolated drill and prove compatibility with the production boundary being opened
- a `reopen` event submitted to preflight must also dereference `actualRecoveryRecordRef`, require `recoveryStatus=ready_to_reopen`, `recoveryPurpose=actual-recovery`, and `trafficExposure=player-facing-reopen`, and match the exact target boundary being reopened
- `restoreDrillLastSuccessAt` must be within 30 days
- `backupCoverage` must be `environment-wide-postgresql`
- the referenced recovery record must prove the exact environment-wide cold-start contract and controlled reopen path
- `PREFLIGHT-BACKUP-002` validates the event while `trafficOpenStatus=authorized`; an idempotent crash-recoverable traffic-open state machine then verifies the same recovery, releases routing through controlled steps, records `trafficOpenedAt`, and advances the records to `finalized`
- retained evidence for a completed first-live/reopen event must use `trafficOpenStatus=finalized`; a merely authorized record is not proof that the transition completed
- the canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml`

Required fields:

- `schemaVersion`
- `environment`
- `status` (`verified` or `recovery-unverified`)
- `lastSuccessfulBackupAt`
- `lastSuccessfulRestoreDrillAt`
- `lastRestoreDrillAt`
- `retentionDailyPoints`
- `backupTooling`
- `lastSuccessfulRecoveryRecordRef`
- `recoveryContractFingerprint`
- `evidenceRefs[]`

`recovery-unverified` records include the operator acknowledgement, timestamp, and reason and make no restore-readiness claim. Verified status requires the supported automated local rehearsal and current evidence. Restore hardening for hobby/self-hosted always fails closed for post-restore player-traffic reopen if verified actual-recovery evidence is missing, stale, or below baseline.

## Hobby Traffic-Open Evidence

Before opening `hobby-self-hosted` to player traffic for the first time, or reopening it after a restore, operators must record traffic-open evidence at:

- `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json`

Required fields:

- `schemaVersion`
- `environment`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`authorized` or `finalized`)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `recoveryPosture` (`verified` or `recovery-unverified`)
- `baselineRecoveryRecordRef` when `recoveryPosture=verified`
- `recoveryUnverifiedAcknowledgement` when a first-live event uses `recovery-unverified`
- `actualRecoveryRecordRef` when `eventType=reopen`
- `preflightReportPath`
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `evidenceRefs[]`

Validation rules:

- a verified first-live event requires `backupComplianceRef` and `baselineRecoveryRecordRef` to point to a current verified record and finalized automated local rehearsal proving the environment-wide `cold_start_restore` contract
- a first-live event may instead use `recoveryPosture=recovery-unverified` with explicit acknowledgement and a clear no-recovery-promise diagnostic; it must not claim verified status
- every reopen event requires verified posture plus the actual recovery record for that restore; the first-live exception never applies after a rewind
- a reopen actual-recovery record must be `ready_to_reopen` when preflight authorizes the event; the gated transition finalizes both records before traffic flows
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- hobby player traffic must not open when the applicable verified or explicitly acknowledged first-live evidence is missing, malformed, or bound to a failed preflight run
- the traffic-open artifact should be written or refreshed for each first-live or reopen event even when the referenced compliance record did not change, so the evidence remains bound to the current deployment or recovery lineage

## Canonical Recovery Record

Every production-equivalent drill and actual player-facing restore produces one canonical recovery record. An actual recovery must advance that record to `ready_to_reopen` before quarantine is lifted and finalize the same record as part of the gated release transition:

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required top-level fields:

- `schemaVersion`
- `environment`
- `recoveryRef`
- `recoveryStatus` (`collecting`, `ready_to_reopen`, or `finalized`)
- `recoveryPurpose` (`production-equivalent-drill` or `actual-recovery`)
- `sourceEnvironmentBinding`
- `targetBoundary`
- `trafficExposure` (`isolated-drill` or `player-facing-reopen`)
- `restoreSource`
- `restoreSafeMode`
- `coordinationRecoveryMode` (`cold_start_restore`)
- `backupArtifactRef`
- `backupArtifactLineage`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `recoveryParticipantInventoryRef`
- `quarantineStartedAt`
- `readyToReopenAt` when `recoveryStatus` is `ready_to_reopen` or `finalized`
- `quarantineReleasedAt` when `recoveryStatus=finalized`
- `finalizedAt` when `recoveryStatus=finalized`
- `restoredAt`
- `restoredBy`
- `recoveryPointApprovedBy` and the displayed effective data-loss window for an actual rewind, or a reference to a separately accepted automatic-DR policy
- `preflightReportPath` when applicable
- `expectedBindingsRef`
- `coordinationRecoveryEvidence`
- `durableParticipantConvergence`
- `externalEffectReconciliation`
- `sessionRecovery`
- `jwtHardening`
- `databaseCredentialRotation`
- `certificateReissuance`
- `externalCredentialValidation`
- `secretComplianceRefresh`
- `sanitizationEvidenceRef` when staging is restored from production-origin data
- `smokeStatus`
- `smokeEvidence`
- `reopenApprovedBy` when `recoveryStatus` is `ready_to_reopen` or `finalized`

Nested control-group requirements:

- `restoreSafeMode` includes evidence that player ingress was disabled, normal background processors and outbound integrations were stopped or restore-safe-fenced, Game Session tick execution and command intake could not create fresh coordination state before the coordination recovery gate, and only approved maintenance Jobs ran before quarantine release
- `jwtHardening` includes rotation job reference, resulting key IDs, issuer auth-generation evidence, and validator-convergence evidence
- `databaseCredentialRotation` includes rotation job reference, affected Secret refs, and rollout-restart completion evidence
- `certificateReissuance` includes workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation` includes one result per credential class with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue`, isolation assertion, and immutable evidence ref
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed, and whether each class used `lastProvisionedAt` or `lastRotationAt`
- `backupArtifactLineage` binds the environment-wide PostgreSQL artifact to its database identity, snapshot time, schema/migration lineage, service digests, and object-storage identity
- `coordinationRecoveryEvidence` proves `cold_start_restore`, an empty Coordination Redis keyspace before rebuild, and environment-wide gameplay-region epoch/fence advancement or recreation
- `durableParticipantConvergence` contains one safe disposition for every declared and enabled participant in the immutable recovery participant inventory: `converged`, `terminalized`, `invalidated`, or `fenced_disabled_backlog_retained`; missing, `unknown`, or `unsafe` participants fail the gate
- `externalEffectReconciliation` records the authoritative safe disposition for each enabled provider-facing workflow family that can straddle the snapshot boundary, including communications, payments, webhooks, and published object-store effects
- `sessionRecovery` proves environment-wide gameplay and Account session invalidation and must use `gameSessionHandling=invalidated` and `authSessionHandling=invalidated`; fresh sessions may be issued only after the reopen gate

Validation rules:

- quarantine remains in place while `recoveryStatus=collecting`; once every required pre-release control group passes and `reopenApprovedBy` is recorded, the controller records `readyToReopenAt` and advances the record to `ready_to_reopen`
- controlled reopen uses durable monotonic progress and idempotent retries to release quarantine and traffic, record `quarantineReleasedAt` and `finalizedAt`, and advance `recoveryStatus` to `finalized`; an interrupted or uncertain transition remains closed with its exact incomplete step recorded
- a `production-equivalent-drill` uses `trafficExposure=isolated-drill`; its controlled reopen authorizes only the isolated test boundary and cannot authorize production traffic
- an `actual-recovery` that will reopen player traffic uses `trafficExposure=player-facing-reopen` and is bound to that exact target boundary
- `coordinationRecoveryMode` must be `cold_start_restore` for player-facing recovery; `scoped_reset_restore` experiments remain quarantined and cannot satisfy this record
- every declared and enabled participant named by `recoveryParticipantInventoryRef` must have a safe disposition; a durable fenced backlog may survive reopen only when the participant remains disabled and its owning recovery contract defines the later operator action
- `readyToReopenAt` must be later than restore-safe-mode entry, coordination recovery, hardening, external-credential validation, secret-compliance refresh, and smoke-check completion times; `quarantineReleasedAt` and `finalizedAt` must be later than `readyToReopenAt`
- traffic reopen is non-compliant if this record is missing, incomplete, or inconsistent with the restore event

Operator credential evidence representation:

- when the expected binding is a platform resource identifier, store that identifier in `observedValue`
- when the expected binding is a certificate or key fingerprint, store that fingerprint in `observedValue`
- do not store competing canonical representations in one result unless one is clearly marked as supporting detail

Illustrative player-facing recovery records must follow the canonical environment-wide `cold_start_restore` shape from the backup architecture baseline so automated drills and actual recovery produce comparable artifacts across environments. Every recovery step is idempotent and resumable from durable state. A quarantined `scoped_reset_restore` experiment must use a distinct non-readiness evidence type and cannot be referenced by production preflight.

## Naming Rule

- `<deployment-ref>` and `<recovery-ref>` use lowercase ASCII plus digits and `-`
- each token remains stable for the single deployment or recovery event it represents
