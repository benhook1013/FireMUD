# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Implementation Notes

Backup-pause metrics are maintenance/reset observability, not player-facing PostgreSQL-recovery proof. They may retain bounded `tenant`/`game-instance`/`region` scope labels and incomplete-scope migration signals for those maintenance workflows, but accepted recovery readiness is proved by one environment-wide recovery-controller lineage. Exact tenant, game-instance, and region identities belong in retained maintenance evidence and control-plane reads, not in the accepted backup-recovery gate.

Backup readiness is an evidence chain, not an artifact-shaped timestamp record. The scheduled dump, restore controller, and preflight must retain complete environment/schema/service/tool lineage and dereference the backup-readiness, baseline, and actual-recovery records independently. `verify-backups.sh` contributes only existence/reachability evidence; immutable lineage, artifact readability, restore-tool compatibility, erasure replay, and player-facing readiness require separate evidence. Player-facing readiness remains blocked until backup-under-write, inventory convergence, hardening, smoke, and controlled-reopen proof is available.

The artifact-integrity, recovery-participant, and reopen-attempt metrics in this document are target-state contracts, not evidence that the current runtime emits them. No reliable emitter for `backup_artifact_lineage_valid`, `backup_artifact_restore_readable`, `recovery_participant_convergence_state`, or `recovery_reopen_attempt_total` is currently implemented or proven. The reference Prometheus ruleset includes fail-safe missing-source alerts, but those alerts cannot identify an environment or participant after an entire family disappears and must not be interpreted as recovery state; partial source loss must remain a blocked readiness condition rather than becoming an apparent pass. Recovery observability remains unproved; the durable recovery controller and retained evidence records remain the readiness authority.

Partial participant-series disappearance is a participant-specific coverage failure, not total family absence. The durable source projection must retain the complete required inventory and coverage state; if that projection cannot be retained, the controller must write an explicit environment/participant readiness blocker and keep quarantine closed. Only complete disappearance of a required source family may use the global `recovery_participant_convergence_source_missing{source_family}` monitoring-gap signal, and that signal must never replace the participant-specific fail-closed blocker.

## Restore-Cutover Evidence Boundary

The restore mode, reopen, and replay boundary is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). This document retains the evidence consequence: before hardening observers or validator probes run, the recovery controller must have one durable restore-cutover identity, and ambiguity keeps quarantine closed.

Evidence records the Account-owned authority/delegation result separately from the Game Session-owned gameplay-binding/session result. The JWT rotation workload only observes and reconciles that operation and writes operation-bound evidence; it cannot create a second cutover, access private material, mutate Account signing state, invalidate sessions, or request validator changes.

## Backup Observability and Alerts

Every readiness or alerting signal is environment-scoped with an `environment` label, except the explicitly global `recovery_participant_convergence_source_missing{source_family}` monitoring-gap signal. That exception has no environment label because it detects total disappearance of a required source family; all other signals below retain the environment label. The environment label identifies the deployment boundary, not a tenant, game instance, or region; convergence signals retain the bounded participant-family dimension defined below:

- `backup_last_success_timestamp_seconds{environment}`
- `backup_verify_last_success_timestamp_seconds{environment}`
- `backup_restore_drill_last_success_timestamp_seconds{environment,mode}`
- `backup_restore_drill_total{environment,result,mode}`
- `backup_artifact_lineage_valid{environment}`
- `backup_artifact_restore_readable{environment}`
- `recovery_participant_convergence_total{environment,participant,result}`
- `recovery_participant_convergence_state{environment,participant,state}` – current participant-family state gauge; this is the readiness signal, not the historical event counter
- `recovery_participant_convergence_coverage{environment,participant,state}` – durable controller projection of source coverage, with `state` equal to `complete`, `source_missing`, or `unknown`
- `recovery_required_participant_inventory{environment,participant}` – controller projection of the authoritative required participant-family set
- `recovery_required_participant_inventory_complete{environment}` – `0` while an environment projection is being refreshed and `1` only when the complete authoritative set is visible
- `recovery_oldest_unresolved_age_seconds{environment,participant}`
- `recovery_environment_convergence_total{environment,result}`
- `recovery_reopen_attempt_total{environment,result,reason}` – bounded `result` and `reason` enums; `reason="incomplete_convergence"` identifies a blocked release attempt
- optional `backup_run_total{environment,result}` and `backup_verify_run_total{environment,result}`

Prometheus should also publish derived breach indicators:

- `backup_pipeline_recent_backup_slo_breached{environment}`
- `backup_pipeline_recent_verification_slo_breached{environment}`
- `backup_pipeline_recent_restore_drill_slo_breached{environment,mode}`
- `backup_artifact_lineage_invalid{environment}`
- `backup_artifact_restore_unreadable{environment}`
- `recovery_participant_convergence_blocked{environment,participant,state}` – a derived recording/alert, not a controller-owned source series
- `recovery_environment_convergence_blocked{environment}`
- `recovery_participant_convergence_coverage_missing{environment,participant}` – records partial source coverage loss while preserving the affected participant family; environment-level inventory-completeness failures use the reserved `participant="__environment__"` sentinel
- `recovery_participant_convergence_source_missing{source_family}` – global monitoring-gap signal only for total disappearance of a required inventory source family; it is not readiness state for any particular environment and must not be emitted for partial participant-series loss

The `participant` metric label is a closed participant-family enum, not a service instance, plugin, tenant-defined name, workflow ID, or other free-form inventory identity. The initial allowed values are:

- `gameplay_commands`
- `tick_effects`
- `remote_followups`
- `automation_work_items`
- `external_effects`

`__environment__` is reserved solely for environment-level inventory-completeness failures. Adding another participant-family value requires an architecture and observability-contract update; exact participant or integration identities within a family remain in the durable recovery-controller inventory, retained evidence, and structured audit/log records.

The `state` label on `recovery_participant_convergence_state` is a closed enum: `blocked`, `converged`, `terminalized`, `invalidated`, or `fenced_disabled_backlog_retained`. The `state` label on `recovery_participant_convergence_coverage` is a closed enum: `complete`, `source_missing`, or `unknown`; `source_missing` and `unknown` are durable blocked states, not scrape-time inferences. The derived `recovery_participant_convergence_blocked` series is produced from the current durable source and coverage state; it is not written as independent controller state. The `source_family` label on `recovery_participant_convergence_source_missing` is also closed; the current values are `inventory_complete` and `participant_inventory`. These labels must not carry arbitrary participant, service, workflow, or inventory-source identities.

The controller owns and writes the source projection: it publishes each environment's required participant-family inventory, current participant state, and explicit coverage state as one all-or-nothing durable projection. It sets the completeness marker to `0` before changing source series, reconciles the full authoritative family set by removing or explicitly tombstoning every family absent from that inventory, including stale `recovery_required_participant_inventory` and `recovery_participant_convergence_state` series, and exposes the complete current set before setting the marker to `1`; a partial projection must never carry `complete=1`. Source loss, stale or unreachable reads, incomplete inventory, and ambiguous results must durably write `recovery_participant_convergence_coverage` with `source_missing` or `unknown` for the affected participant or the reserved `__environment__` entry and keep readiness blocked. Prometheus exporters expose those durable states; alert rules must use the current durable coverage/blocker state and must not depend on a previous sample surviving through implicit Prometheus last-value retention. `recovery_participant_convergence_blocked` is the union of explicit current participant `blocked` state while the inventory completeness marker equals `1` and `recovery_participant_convergence_coverage_missing`, including the reserved environment blocker emitted whenever completeness differs from `1`. Consequently, stale converged or blocked participant series cannot make an incomplete replacement projection appear ready, and `recovery_environment_convergence_blocked` must aggregate that union rather than the state gauge alone. Within the controller-projection family, only `recovery_participant_convergence_coverage_missing`, `recovery_participant_convergence_blocked`, and the global `recovery_participant_convergence_source_missing` recording are derived-only outputs. The exporter-derived breach indicators `backup_pipeline_recent_*`, `backup_artifact_lineage_invalid`, `backup_artifact_restore_unreadable`, and `recovery_environment_convergence_blocked` are also derived indicators and are not controller-owned source series. The `recovery_participant_convergence_coverage_missing` recording intentionally has only `environment` and `participant` labels: it identifies missing coverage and has no `state` label. The global source-missing recording is derived separately only when the complete required source family disappears and carries only `source_family`; partial source loss uses the durable coverage state and cannot be treated as total family absence. Because a missing source family has no remaining environment label, it cannot replace environment-specific source health or readiness proof. The cumulative `recovery_participant_convergence_total` event counter is audit history only and must not drive an active blocked alert.

Alerting policy:

- missed backups are `P1`
- missed verification is `P1` or `P2` depending on environment class
- invalid artifact lineage, unreadable artifacts, and blocked recovery-participant convergence are `P1` while recovery remains quarantined
- an attempted reopen with incomplete cold-start convergence is `P0`
- stale restore-drill proof is `P1`

The canonical backup/recovery severity matrix lives in `system-architecture-backup-recovery.md`. This document and Grafana alert snippets must mirror that matrix rather than redefining severities independently.

Prometheus and Alertmanager should also carry clear `service`, `severity`, `owner`, and `runbook` annotations on these alerts, and Grafana should include a dedicated Backups section or dashboard that visualizes freshness, artifact lineage/readability, restore-proof age, restore-drill results, and recovery-participant convergence.

Maintenance pause metrics remain owned by the region maintenance/reset contracts and use canonical bounded `scope_type` and `scope` labels there. Routine backup dashboards must not interpret absence of pause spans or pause metrics as a backup failure, and pause-budget breaches do not establish PostgreSQL-recovery readiness.

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
- `baselineRecoveryRecordRef`
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `artifactErasureHighWater`
- `initialCatchupHighWater`
- `restoreHighWater`
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
- `recoveryControllerLineage` must dereference the finalized environment-wide controller state and its immutable backup, restore-tool, participant, hardening, confidentiality, and smoke evidence; tenant/game-instance/region backup-pause proof is not required or sufficient
- `backupConfidentialityEvidence` must prove encrypted transport and storage, environment-scoped least-privilege access and audit, and retention/secure deletion. Whenever production-origin data is exercised outside production, it must also prove quarantine, sanitization, validation, and deletion
- `backupCoverage` must be `environment-wide-postgresql`; a tenant/game-instance/region scope cannot stand in for the whole database
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

Before opening production to player traffic for the first time, or reopening it after any PostgreSQL restore or rewind, preflight must consume proof that the backup pipeline is already functioning for that environment. A restore into a fresh environment boundary is subject to the same gate plus its new-boundary binding checks. The durable recovery controller owns the operation lifecycle and release boundary; the exporter records the traffic-open projection only after that controller finalizes the release.

Traffic-open evidence has two distinct forms. The operation-bound pre-release evidence record identified by `evidenceRef` is immutable evidence for the exact recovery operation, scope, and event and is consumed by the controller's owner-defined continuation/release controls. The checked-in traffic-open record below is a separate immutable post-finalization projection. It is retained evidence, not the pre-release `evidenceRef` record and not an input to the release that produced it. The exact operation-bound `evidenceRef` consumed by the controller must be traceable from the finalized projection's `evidenceRefs[]`, together with any lineage required by the operation-bound record.

At the owner's `ready_to_reopen` boundary, the operation-bound pre-release evidence record uses its own `schemaVersion=traffic-open-pre-release-evidence/v1` and binds the exact operation/event tuple that the exporter must preserve in the finalized projection: `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, and `playerFacingTargetBoundary`. The finalized projection must exact-match `eventType`, `deploymentEventId`, `preflightReportPath`, and the remaining operation/event tuple. It records `projectionSchemaVersion=traffic-open-record/v1` for that finalized projection, which uses `schemaVersion=traffic-open-record/v1`; the consumed operation-bound `evidenceRef` must appear in the finalized projection's `evidenceRefs[]`. The recovery owner defines the continuation call shape; the projection is written only after finalization and is not consumed as release authority.

This is target-state behavior. The current production preflight has no durable controller read and intentionally fails `PREFLIGHT-BACKUP-002` closed; no production traffic-open projection writer is implemented.

Canonical evidence path:

- `design/operations/deployments/production/traffic-open/<first-live|reopen>-<deployment-ref>/<deploymentEventId>.json`

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment`
- `operationId`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; pre-finalization controller state is not represented by this record)
- `deploymentRef`
- `deploymentEventId` (must equal the referenced preflight report)
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupStorageBinding`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `backupReadinessRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen; the checked-in projection is exported after finalization)
- `playerFacingTargetBoundary`
- `backupCoverage`
- `backupArtifactRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `sourceEnvironmentBinding`
- `drillTargetBoundary`
- `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`, matching `eventType`; any referenced baseline drill retains `isolated-drill` in its own recovery record)
- `backupConfidentialityEvidence`
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `evidenceRefs[]`

Validation rules:

- backup and verification evidence must bind to the production source lineage; preflight must dereference `backupReadinessRef`, validate the backup-readiness artifact's own `restoreRecoveryRecordRef`, and independently dereference `baselineRecoveryRecordRef`. Both referenced records must be finalized isolated drills compatible with the boundary being opened
- an isolated production-equivalent drill may run in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its evidence is bound only to that isolated boundary
- the durable actual-recovery controller owns the pre-release and release lifecycle for first-live and reopen. Any preflight consumer must compare the immutable operation-bound evidence and event-matching controller lineage, and must not accept a transient traffic-open file as authority
- `deploymentEventId` must equal the referenced preflight report so every retry, first-live attempt, or reopen has a unique immutable projection and cannot overwrite or reuse another event's evidence
- the finalized projection must retain `schemaVersion=traffic-open-record/v1`, and its `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, and `playerFacingTargetBoundary` must exact-match the consumed operation-bound evidence and finalized controller lineage; it must include the exact operation-bound `evidenceRef` in `evidenceRefs[]`; where the operation-bound record requires lineage, that lineage must remain traceable there. A missing, mutable, or reissued projection is a later evidence-integrity failure, not a release input
- the retained projection exported after release uses `trafficOpenStatus=finalized` and must dereference the same actual-recovery record in `finalized`; `trafficOpenedAt` is required only for this form
- `restoreDrillLastSuccessAt` must be within 30 days
- `backupCoverage` must be `environment-wide-postgresql`
- the referenced recovery record must prove the exact environment-wide cold-start contract and controlled reopen path
- `backupConfidentialityEvidence` must prove the backup confidentiality invariant and, whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion evidence
- `PREFLIGHT-BACKUP-002` validates the backup/recovery lineage and event identity against immutable controller-owned evidence; the durable recovery controller remains the sole continuation and release authority described in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Checked-in projections are retained evidence and never replace live controller authority.
- the exporter writes the checked-in traffic-open projection, including `trafficOpenedAt`, only after the controller reaches `finalized`; the projection is not a prerequisite for that same release, and a runtime authorization or partially written file is not proof that the transition completed
- the exporter must create one new immutable traffic-open projection per `deploymentEventId` after the controller finalizes every first-live or reopen event; an export retry may reuse the event path only when the existing payload exactly matches the finalized event tuple and content digest. It must not mutate or refresh an existing projection, and any mismatch or attempt to reuse a projection for another event is an evidence-integrity failure; a finalized projection cannot be reused for a later event
- the canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml`

Required fields:

- `schemaVersion`
- `environment`
- `status` (`pass` or `incomplete`; only `pass` can contribute to traffic-open authorization)
- `lastSuccessfulBackupAt`
- `lastSuccessfulRestoreDrillAt`
- `lastRestoreDrillAt`
- `retentionDailyPoints`
- `backupTooling`
- `lastSuccessfulRecoveryRecordRef`
- `recoveryContractFingerprint`
- `evidenceRefs[]`

An `incomplete` record is the canonical blocking representation while required recovery proof is unavailable. Its `lastSuccessfulRecoveryRecordRef` and `recoveryContractFingerprint` may be `null`; a `pass` record requires both fields to resolve to the qualifying finalized recovery evidence. Restore hardening for hobby/self-hosted must fail closed for player-traffic reopen if this record is incomplete, missing, stale, or below baseline.

## Hobby Traffic-Open Evidence

The `hobby-self-hosted` operation-bound pre-release record reuses the complete production `traffic-open-pre-release-evidence/v1` schema defined above, including `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, and `playerFacingTargetBoundary`; it carries `projectionSchemaVersion=traffic-open-record/v1`, and the consumed `evidenceRef` must appear in the finalized projection's `evidenceRefs[]`. After the durable controller finalizes a `hobby-self-hosted` first-live or reopen event, the exporter records the retained traffic-open projection at:

- `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>/<deploymentEventId>.json`

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment`
- `operationId`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; pre-finalization controller state is not represented by this record)
- `deploymentRef`
- `deploymentEventId` (must equal the referenced preflight report)
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen)
- `playerFacingTargetBoundary`
- `preflightReportPath`
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `evidenceRefs[]`

Validation rules:

- `backupComplianceRef` must point to a current compliant record
- `baselineRecoveryRecordRef` must point to a finalized exported projection of an isolated drill proving the environment-wide `cold_start_restore` contract for the player-facing hobby boundary; first-live and reopen must additionally reference the durable actual-recovery controller for the live boundary
- the actual-recovery controller lineage must use event-matching `trafficExposure` and name the exact `playerFacingTargetBoundary`; after the controller reaches its owner-defined finalized state, the exporter writes the checked-in projections
- `deploymentEventId` must equal the referenced preflight report so evidence cannot be reused across retries or traffic-open events
- the finalized projection must retain `schemaVersion=traffic-open-record/v1`, and its `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, and `playerFacingTargetBoundary` must exact-match the consumed operation-bound evidence and finalized controller lineage; it must include the exact operation-bound `evidenceRef` in `evidenceRefs[]`; where the operation-bound record requires lineage, that lineage must remain traceable there. A missing, mutable, or reissued projection is a later evidence-integrity failure, not a release input
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- `PREFLIGHT-BACKUP-003` validates immutable pre-release evidence, compliance lineage, and the event-matching actual-recovery controller reference; it does not perform or authorize controller continuation or release. The preflight result must reject missing or stale compliance/controller evidence and any deployment, event, baseline-recovery, or actual-recovery lineage that does not match the current traffic-open event
- the current event's checked-in traffic-open projection is not a preflight input because it is produced only after the controller records its finalized state
- hobby player traffic must not open when this evidence is missing, stale, mismatched, or bound to a failed preflight run
- the exporter must create one new immutable traffic-open projection per `deploymentEventId` after the controller finalizes every first-live or reopen event, even when the referenced compliance record did not change; an export retry may reuse the event path only when the existing payload exactly matches the finalized event tuple and content digest. It must not mutate or refresh an existing projection, and any mismatch or attempt to reuse a projection for another event is an evidence-integrity failure; a finalized projection cannot be reused for a later event

## Canonical Recovery Record

The recovery controller lifecycle and fixed replay boundary are canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). The operation-bound pre-release evidence record identified by `evidenceRef` is distinct from the checked-in records defined below and uses its own `schemaVersion=recovery-pre-release-evidence/v1`; it carries `projectionSchemaVersion=recovery-record/v1` for the finalized projection. Those checked-in records are immutable evidence projections exported only after the controller's owner-defined finalization, use `schemaVersion=recovery-record/v1`, and are not runtime authority or release-transaction inputs. This section owns their evidence schema, lineage, participant dispositions, hardening results, and compliance fields.

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required top-level fields:

- `schemaVersion` (`recovery-record/v1`)
- `environment`
- `recoveryRef`
- `operationId`
- `recoveryStatus` is `finalized` in every checked-in recovery record. Intermediate durable controller phases are not represented by this checked-in field; an intermediate controller projection, if needed for live operations, must use a separate schema and path and is not retained recovery evidence.
- `recoveryPurpose` (`production-equivalent-drill` or `actual-recovery`)
- `sourceEnvironmentBinding`
- `targetBoundary`
- `trafficExposure` (`isolated-drill` for a production-equivalent drill, `player-facing-first-live` for an actual recovery opening the first live player boundary, or `player-facing-reopen` for an actual recovery reopening that boundary)
- `restoreSource`
- `restoreSafeMode`
- `coordinationRecoveryMode` (`cold_start_restore`)
- `backupArtifactRef`
- `artifactErasureHighWater`
- `initialCatchupHighWater`
- `restoreHighWater`
- `erasureReplay`
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
- `deploymentEventId` when an actual-recovery record opens a player-facing boundary; it must match the event-scoped preflight report and traffic-open projection
- `recoveryControllerLineage`
- `expectedBindingsRef`
- `coordinationRecoveryEvidence`
- `backupConfidentialityEvidence`
- `durableParticipantConvergence`
- `externalEffectReconciliation`
- `erasureOverlayReconciliation`
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
- `evidenceRefs[]`

Nested control-group requirements:

- `restoreSafeMode` includes evidence that player ingress was disabled, normal background processors and outbound integrations were stopped or restore-safe-fenced, Game Session tick execution and command intake could not create fresh coordination state before the coordination recovery gate, and only approved maintenance Jobs ran before quarantine release
- `jwtHardening` includes the rotation job reference, the stable `restoreCutoverOperationId`, the immutable restore-cutover request digest, resulting key IDs, the recorded compromise/reset `issuerAuthorityGeneration` advance, explicitly distinguished from per-token `tokenGeneration` and any other authority generation, and proof that issued-token registry snapshots whose `issuerAuthorityGeneration` is older than that recorded compromise/reset generation are rejected. It also includes bounded physical registry/session cleanup status; cleanup may delete only old or stale projections after the Account authority cutover and before replacement registration, replacement registry records remain after registration, and cleanup is required retained evidence but is not a wildcard-scan revocation authority
- `validatorInventoryRef` points to an authoritative, complete, reachable inventory. Every validator must have a safe converged result and must receive public JWKS only; missing, unknown, unreachable, or private-key-access results fail recovery
- Post-restore JWT rotation preserves Account Service custody of non-exportable private signing material and JWKS publication authority. Rotation Jobs observe the Account-owned generation transition and published public JWKS but do not request the transition, write `jwt-jwks`, read or export private keys, or mutate Account signing state; recovery evidence must never contain private signer material
- `databaseCredentialRotation` includes rotation job reference, affected Secret refs, and rollout-restart completion evidence
- `certificateReissuance` includes workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation` includes one result per credential class with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue`, isolation assertion, and immutable evidence ref. `observedValue` is explicitly non-secret and is limited to a resource ID, certificate/key fingerprint, or redacted presence indicator; it must never contain a password, token, private key, raw secret material, or an unredacted credential-bearing connection string
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed, and whether each class used `lastProvisionedAt` or `lastRotationAt`
- for a staging restore of production-origin data, `sanitizationEvidenceRef` identifies an immutable pre-release `recovery-sanitization-evidence/v1` artifact whose `recoveryRef`, `operationId`, `deploymentEventId`, and `backupArtifactDigest` match the durable controller and restored artifact. The finalized recovery projection references that same artifact; it is not used circularly as its own pre-release evidence
- `recoveryControllerLineage` identifies the durable controller state, environment-wide scope, linked artifact and participant lineage, pre-release `ready_to_reopen` approval, and post-release `finalized` state when this projection is exported
- `backupConfidentialityEvidence` uses `status=pass`, `transport=encrypted`, and `storage=encrypted` and proves environment-scoped least-privilege access and audit plus retention/secure deletion. Whenever production-origin data is exercised outside production, it also proves quarantine, sanitization, validation, and deletion
- `backupArtifactLineage` binds the environment-wide PostgreSQL artifact to its immutable digest, database identity, non-empty `snapshotIdentity` and parseable `snapshotAt`, schema/migration lineage, service digests, object-storage identity, the separately sourced `preSnapshotJournalHighWater`, and the same `artifactErasureHighWater`; `preSnapshotJournalHighWater` includes non-empty `observationId`, parseable `observedAt`, and a non-empty `observationDigest` prefixed with `sha256:`. Its sibling `preSnapshotJournalBoundaryWitness` is immutable evidence with matching `observationId`, `observationDigest`, and `snapshotIdentity`, `snapshotOpenedAt` exactly equal to `snapshotAt`, and a non-empty `evidenceRef`; `observedAt` must strictly precede that snapshot opening. Together they prove that the pre-snapshot journal observation was recorded before the snapshot opened and either that its sequence is at or above the artifact high-water captured inside that same database snapshot or, when it is lower, that immutable `interveningErasureCoverageProof` represents every sequence in `(preSnapshotJournalHighWater, artifactErasureHighWater]` exactly once with matching identity/digest in both the snapshot-visible erasure ledger and external journal. The proof carries the canonical `stream`, exact `exclusiveStart` and `inclusiveEnd`, non-empty `snapshotLedgerEvidenceRef` and `externalJournalEvidenceRef`, and an ordered `entries[]` covering the interval exactly once. Each entry carries `sequence` plus `snapshotVisibleLedger` and `externalJournal` objects whose non-empty `identity` and `digest` values must match exactly. The proof must be absent when `preSnapshotJournalHighWater >= artifactErasureHighWater`. Any gap, duplicate, unknown, ambiguous, unnecessary, or non-matching intervening entry fails validation. The artifact bytes and digest are stored first, one immutable manifest binds that digest to the snapshot, both high-water observations, and the applicable boundary proof second, and an atomic or compare-and-set ready-publication record makes that set eligible for recovery last. Missing, mutable, duplicate, partially published, or non-matching objects fail validation
- `artifactErasureHighWater` is the snapshot-bound source high-water, `initialCatchupHighWater` is captured immutably when recovery catch-up starts, and `restoreHighWater` is captured immutably by the bounded final cutover as the readiness boundary. All three come from the same authoritative ledger and must satisfy `restoreHighWater >= initialCatchupHighWater >= artifactErasureHighWater`; none may be inferred from restored PostgreSQL
- `erasureReplay` identifies the authoritative ledger, exclusive start (`artifactErasureHighWater`), initial catch-up boundary (`initialCatchupHighWater`), inclusive final end (`restoreHighWater`), replayed-through sequence, gap-free completion evidence, bounded final-cutover evidence, and the installed online-consumer cursor. The initial interval `(artifactErasureHighWater, initialCatchupHighWater]` must be complete before final cutover. During final cutover, the erasure authority fences or serializes new sequence assignment, captures `restoreHighWater`, replays `(initialCatchupHighWater, restoreHighWater]`, and atomically hands off that fixed value as the online-consumer cursor before releasing the fence. The restore overlay replay contains every erasure event only through the fixed `restoreHighWater`, in order and without gaps or unknown entries; later deletions use normal online consumer/reconciliation and are not added to the restore overlay evidence. The final cutover must prove atomic handoff to normal processing before the controller reaches `ready_to_reopen`; downstream participant quarantine and reset/fence evidence remain required until the same readiness boundary.
- `coordinationRecoveryEvidence` uses `mode=cold_start_restore`, `coordinationRedis=empty-before-rebuild`, `credentialBinding=rotated-or-rebound`, `targetEnvironmentBound=true`, `snapshotCredentialsRejected=true`, `regionEpochFences=advanced-or-recreated`, `accountAuthorityProjections=rebuilt-and-verified`, a non-empty `accountAuthorityProjectionEvidenceRef`, `replayAdmissionFence=advanced`, `replayQuarantine=lifetime-plus-skew-observed`, and a non-empty `replayConsumeEvidenceRef`. Together these fields prove an empty Coordination Redis keyspace, fresh target-environment credentials, environment-wide gameplay-region epoch/fence advancement or recreation, Account issuer/account/tenant/membership-generation and exact issued-token projection rebuild from durable authority, and safe replay admission before authorization. Missing, stale, malformed, generation-mismatched, or ambiguous evidence fails the gate.
- `recoveryParticipantInventoryRef` and `externalEffectInventoryRef` each point to authoritative, complete, reachable inventories. `durableParticipantConvergence` and `externalEffectReconciliation` must contain one qualifying disposition for every declared and enabled entry: `converged`, `terminalized`, or `invalidated`; `fenced_disabled_backlog_retained` remains representable as a non-qualifying, quarantine-blocking state and can never satisfy this gate. Missing, unknown, unreachable, or unsafe entries fail the gate
- `erasureOverlayReconciliation` records the three immutable erasure boundaries. Its ordered `sequenceVerification` proves the earlier interval `(artifactErasureHighWater, initialCatchupHighWater]`; its `sequenceDispositions[]` is bound exactly to the final cutover interval `(initialCatchupHighWater, restoreHighWater]` and contains exactly one qualifying owner disposition for every erasure sequence in that interval, using `converged`, `terminalized`, or `invalidated`. A `fenced_disabled_backlog_retained` entry is non-qualifying and keeps quarantine closed. Aggregate counts or a replay-through high-water without the ordered earlier proof and final per-sequence dispositions are insufficient; any gap, duplicate, unverifiable record, unavailable owner, or incomplete replay fails the gate
- `sessionRecovery` proves environment-wide recovery for both authority owners: Account invalidates Account authority and private delegation lineages, while Game Session invalidates gameplay bindings and gameplay session state. Evidence must separately reconcile both sides and use `gameSessionHandling=invalidated` and `authSessionHandling=invalidated`; fresh sessions may be issued only after the reopen gate

Validation rules:

- The operation-bound `evidenceRef` supplied through the recovery owner's canonical continuation path must identify the exact qualifying pre-release evidence record for that operation and event; it must not identify this post-finalization checked-in projection. The exact consumed `evidenceRef` must appear in the finalized projection's `evidenceRefs[]`, with any lineage required by the operation-bound record remaining traceable there. The operation-bound scope, immutable evidence lineage, Account projection evidence, replay-domain proof, session-policy result, and post-restore control-group evidence must be complete before this record can qualify. The recovery owner defines the continuation call shape and release semantics; this document defines only the evidence identity and qualifying projection.
- The operation-bound scope, server-issued lock identity, immutable evidence lineage, Account projection evidence, replay-domain proof, session-policy result, and post-restore control-group evidence must match the durable controller. Missing, stale, mismatched, or ambiguous evidence keeps this record non-qualifying and player traffic closed.
- A failed or ambiguous release effect, incomplete per-effect readback, or partially applied release cannot produce a qualifying finalized controller lineage or an exported traffic-open projection. The controller's owner-defined reconciliation remains the source of that result; this document records only the immutable evidence consequence.
- a `production-equivalent-drill` uses `trafficExposure=isolated-drill`; its controlled reopen authorizes only the isolated test boundary and cannot authorize production traffic
- an `actual-recovery` that will open player traffic uses event-matching `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`) and is bound to that exact target boundary
- an actual-recovery record that opens player traffic must bind `deploymentEventId` and `preflightReportPath` to the same event-scoped preflight report as the traffic-open projection; production-equivalent drills omit `deploymentEventId` and cannot authorize player traffic
- `coordinationRecoveryMode` must be `cold_start_restore` for player-facing recovery; `scoped_reset_restore` experiments remain quarantined and cannot satisfy this record
- every declared and enabled participant named by `recoveryParticipantInventoryRef` must have a qualifying disposition; `fenced_disabled_backlog_retained` is a representable but non-qualifying, quarantine-blocking state and cannot survive player-facing reopen as recovery completion
- exported projections preserve the controller-defined `readyToReopenAt`, `quarantineReleasedAt`, and `finalizedAt` values and finalized release identity and exact-match the operation/event-bound evidence tuple. This evidence schema does not impose an ordering relation among lifecycle timestamps; lifecycle ordering is validated by [Backup & Disaster Recovery](./system-architecture-backup-recovery.md#recovery-controller-continuation), not redefined here.
- traffic reopen is non-compliant if the durable controller state is missing, incomplete, or inconsistent with the restore event; a missing or mutable post-finalization projection is a later evidence-integrity failure, not a reason to create a circular pre-release dependency

Operator credential evidence representation:

- `observedValue` may contain only a platform/resource identifier, certificate/key fingerprint, or redacted presence indicator such as `present` or `redacted`
- never store passwords, tokens, private keys, raw secret material, or unredacted credential-bearing connection strings in `observedValue` or its supporting evidence
- when the expected binding is a platform resource identifier, store that identifier in `observedValue`
- when the expected binding is a certificate or key fingerprint, store that fingerprint in `observedValue`
- do not store competing canonical representations in one result unless one is clearly marked as supporting detail

Illustrative player-facing recovery records must follow the canonical environment-wide `cold_start_restore` shape from the backup architecture baseline so automation and manual drills produce comparable artifacts across environments. A quarantined `scoped_reset_restore` experiment must use a distinct non-readiness evidence type and cannot be referenced by production preflight.

## Naming Rule

- `<deployment-ref>` and `<recovery-ref>` use lowercase ASCII plus digits and `-`
- each token remains stable for the single deployment or recovery event it represents
