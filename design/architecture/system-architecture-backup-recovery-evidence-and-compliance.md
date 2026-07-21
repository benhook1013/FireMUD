# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Implementation Notes

Backup readiness is an evidence chain, not an artifact-shaped timestamp record. The scheduled dump, restore controller, and preflight must retain complete environment/schema/service/tool lineage and dereference the backup-readiness, baseline, and actual-recovery records independently. Player-facing readiness remains blocked until backup-under-write, inventory convergence, hardening, smoke, and controlled-reopen proof is available.

## Backup Observability and Alerts

Backup and verification jobs must emit simple metrics with an `environment` label on every signal that feeds readiness or alerting. The environment label identifies the deployment boundary, not a tenant or region; convergence signals retain participant dimensions where they are available:

- `backup_last_success_timestamp_seconds{environment}`
- `backup_verify_last_success_timestamp_seconds{environment}`
- `backup_restore_drill_last_success_timestamp_seconds{environment,mode}`
- `backup_restore_drill_total{environment,result,mode}`
- `backup_artifact_lineage_valid{environment}`
- `backup_artifact_restore_readable{environment}`
- `recovery_participant_convergence_total{environment,participant,result}`
- `recovery_oldest_unresolved_age_seconds{environment,participant}`
- `recovery_environment_convergence_total{environment,result}`
- optional `backup_run_total{environment,result}` and `backup_verify_run_total{environment,result}`

Prometheus should also publish derived breach indicators:

- `backup_pipeline_recent_backup_slo_breached{environment}`
- `backup_pipeline_recent_verification_slo_breached{environment}`
- `backup_pipeline_recent_restore_drill_slo_breached{environment,mode}`
- `backup_artifact_lineage_invalid{environment}`
- `backup_artifact_restore_unreadable{environment}`
- `recovery_participant_convergence_blocked{environment,participant,result}`
- `recovery_environment_convergence_blocked{environment}`

Derived indicators and alerts must preserve these labels and group by `environment`; participant convergence alerts must not reduce an environment-wide failure to an unlabeled boolean or discard the failing `participant` or `result`.

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

- `backupLastSuccessAt` within 90 minutes of production preflight
- `backupVerifyLastSuccessAt` within 36 hours
- `restoreDrillLastSuccessAt` within 30 days; a waiver may postpone an isolated drill or salvage exercise but cannot authorize player-facing first-live, post-rewind reopen, or `roll-forward-only` promotion without the required proof

Evidence reuse and invalidation:

- rollback-compatible steady-state releases use the compact recovery-compatibility result below and need not duplicate this full record when reuse is valid
- an invalidating recovery-contract change requires a new production-equivalent drill even when the 30-day window has not expired
- a `roll-forward-only` release never reuses only the cadence record; its source artifact comes from the current production database lineage under representative writes and is restored using candidate recovery tooling, exact candidate service digests, the candidate migration path, and candidate config/bindings
- first-live and reopen events require environment-specific proof for the boundary being opened

Validation rules:

- evidence must match the promoted attestation
- `restoreRecoveryRecordRef` must point to a finalized exported projection of a `production-equivalent-drill` controller state with `trafficExposure=isolated-drill` that completed quarantine, post-restore hardening, external credential validation, smoke verification, and the isolated controlled-reopen transition
- `backupReadinessRef` is a backup-readiness artifact, not a recovery record. Preflight must dereference it and separately dereference and validate its `restoreRecoveryRecordRef`; that restore record does not replace validation of `baselineRecoveryRecordRef`
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

Before opening production to player traffic for the first time, or reopening it after restore into a fresh environment boundary, operators must record proof that the backup pipeline is already functioning for that environment.

Traffic-open evidence is a separate event-bound projection around the canonical recovery and backup-readiness evidence. Preflight authorizes the event from the durable recovery controller; the writer exports the checked-in immutable projection only after the controller has observed/applied the release and reached `finalized`.

Canonical evidence path:

- `design/operations/deployments/production/traffic-open/<first-live|reopen>-<deployment-ref>.json`

Required fields:

- `schemaVersion`
- `environment`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; the controller may hold a runtime authorization before release)
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
- `actualRecoveryRecordRef` when `eventType=reopen` (durable actual-recovery controller reference; the checked-in projection is exported after finalization)
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

- backup and verification evidence must bind to the production source lineage; preflight must dereference `backupReadinessRef`, validate the backup-readiness artifact's own `restoreRecoveryRecordRef`, and independently dereference `baselineRecoveryRecordRef`. Both referenced records must be finalized isolated drills compatible with the boundary being opened
- an isolated production-equivalent drill may run in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its controlled reopen authorizes only that isolated boundary
- a `reopen` event submitted to preflight must also dereference the durable controller named by `actualRecoveryRecordRef`, require its state to be `ready_to_reopen` with `recoveryPurpose=actual-recovery` and `trafficExposure=player-facing-reopen`, and use that record as the exact boundary proof for reopen
- `restoreDrillLastSuccessAt` must be within 30 days
- `backupCoverage` must be `environment-wide-postgresql`
- the referenced recovery record must prove the exact environment-wide cold-start contract and controlled reopen path
- `PREFLIGHT-BACKUP-002` validates the event against the durable controller while the actual recovery is `ready_to_reopen`; the controller idempotently reconciles `ready_to_reopen -> releasing -> finalized`, applying and observing quarantine release before permitting player traffic
- the exporter writes the checked-in traffic-open projection, including `trafficOpenedAt`, only after the controller reaches `finalized`; a runtime authorization or partially written file is not proof that the transition completed
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
- `lastSuccessfulRecoveryRecordRef`
- `recoveryContractFingerprint`
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
- hobby player traffic must not open when this evidence is missing, stale, or bound to a failed preflight run
- the traffic-open artifact should be written or refreshed for each first-live or reopen event even when the referenced compliance record did not change, so the evidence remains bound to the current deployment or recovery lineage

## Canonical Recovery Record

Every production-equivalent drill and actual player-facing restore has one durable recovery-controller state machine. The checked-in records below are immutable projections exported only after that controller reaches `finalized`; they are not runtime authority or release-transaction inputs. For an actual recovery, preflight reads the controller in `ready_to_reopen`, and the controller owns the release reconciliation:

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required top-level fields:

- `schemaVersion`
- `environment`
- `recoveryRef`
- `recoveryStatus` (`finalized` in the checked-in projection; the runtime controller also uses `collecting`, `ready_to_reopen`, and `releasing`)
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
- `validatorInventoryRef`
- `externalEffectInventoryRef`
- `quarantineStartedAt`
- `readyToReopenAt` when the controller reached `ready_to_reopen`
- `quarantineReleasedAt` when the controller observed the release
- `finalizedAt` when the controller reached `finalized`
- `restoredAt`
- `restoredBy`
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
- `reopenApprovedBy` when the controller reached `ready_to_reopen`

Nested control-group requirements:

- `restoreSafeMode` includes evidence that player ingress was disabled, normal background processors and outbound integrations were stopped or restore-safe-fenced, Game Session tick execution and command intake could not create fresh coordination state before the coordination recovery gate, and only approved maintenance Jobs ran before quarantine release
- `jwtHardening` includes rotation job reference, resulting key IDs, revocation watermark evidence, and validator-convergence evidence
- `validatorInventoryRef` points to an authoritative, complete, reachable inventory. Every validator must have a safe converged result and must receive public JWKS only; missing, unknown, unreachable, or private-key-access results fail recovery
- Post-restore JWT rotation preserves Account Service custody of non-exportable private signing material. Rotation Jobs may request or publish a new Account generation and public JWKS, but they do not read or export private keys; recovery evidence must never contain private signer material
- `databaseCredentialRotation` includes rotation job reference, affected Secret refs, and rollout-restart completion evidence
- `certificateReissuance` includes workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation` includes one result per credential class with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue`, isolation assertion, and immutable evidence ref
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed, and whether each class used `lastProvisionedAt` or `lastRotationAt`
- `backupArtifactLineage` binds the environment-wide PostgreSQL artifact to its database identity, snapshot time, schema/migration lineage, service digests, and object-storage identity
- `coordinationRecoveryEvidence` proves `cold_start_restore`, an empty Coordination Redis keyspace before rebuild, and environment-wide gameplay-region epoch/fence advancement or recreation
- `recoveryParticipantInventoryRef` and `externalEffectInventoryRef` each point to authoritative, complete, reachable inventories. `durableParticipantConvergence` and `externalEffectReconciliation` must contain one safe disposition for every declared and enabled entry: `converged`, `terminalized`, `invalidated`, or `fenced_disabled_backlog_retained`; missing, unknown, unreachable, or unsafe entries fail the gate
- `sessionRecovery` proves environment-wide gameplay and Account session invalidation and must use `gameSessionHandling=invalidated` and `authSessionHandling=invalidated`; fresh sessions may be issued only after the reopen gate

Validation rules:

- quarantine remains in place while the controller is `collecting`; once every required pre-release control group passes and `reopenApprovedBy` is recorded, the controller records `readyToReopenAt` and advances its durable state to `ready_to_reopen`
- a release request is idempotently reconciled from `ready_to_reopen` to `releasing`; the controller repeatedly applies and observes the quarantine-routing release while keeping traffic closed. Any failed or ambiguous apply remains fail-closed in `releasing` and cannot produce `finalized`
- after the controller applies and observes the release, it advances its durable state to `finalized`; only then may actual player traffic flow, and only then may the checked-in recovery and traffic-open projections be exported
- a `production-equivalent-drill` uses `trafficExposure=isolated-drill`; its controlled reopen authorizes only the isolated test boundary and cannot authorize production traffic
- an `actual-recovery` that will reopen player traffic uses `trafficExposure=player-facing-reopen` and is bound to that exact target boundary
- `coordinationRecoveryMode` must be `cold_start_restore` for player-facing recovery; `scoped_reset_restore` experiments remain quarantined and cannot satisfy this record
- every declared and enabled participant named by `recoveryParticipantInventoryRef` must have a safe disposition; a durable fenced backlog may survive reopen only when the participant remains disabled and its owning recovery contract defines the later operator action
- `readyToReopenAt` must be later than restore-safe-mode entry, coordination recovery, hardening, external-credential validation, secret-compliance refresh, and smoke-check completion times; `quarantineReleasedAt` and `finalizedAt` must be later than `readyToReopenAt`, and exported projections must carry the controller's finalized release identity
- traffic reopen is non-compliant if this record is missing, incomplete, or inconsistent with the restore event

Operator credential evidence representation:

- when the expected binding is a platform resource identifier, store that identifier in `observedValue`
- when the expected binding is a certificate or key fingerprint, store that fingerprint in `observedValue`
- do not store competing canonical representations in one result unless one is clearly marked as supporting detail

Illustrative player-facing recovery records must follow the canonical environment-wide `cold_start_restore` shape from the backup architecture baseline so automation and manual drills produce comparable artifacts across environments. A quarantined `scoped_reset_restore` experiment must use a distinct non-readiness evidence type and cannot be referenced by production preflight.

## Naming Rule

- `<deployment-ref>` and `<recovery-ref>` use lowercase ASCII plus digits and `-`
- each token remains stable for the single deployment or recovery event it represents
