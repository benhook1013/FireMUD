# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

## Implementation Notes

Backup-pause metrics are maintenance/reset observability, not player-facing PostgreSQL-recovery proof. They may retain bounded `tenant`/`game-instance`/`region` scope labels and incomplete-scope migration signals for those maintenance workflows, but accepted recovery readiness is proved by one environment-wide recovery-controller lineage. Exact tenant, game-instance, and region identities belong in retained maintenance evidence and control-plane reads, not in the accepted backup-recovery gate.

Backup readiness is an evidence chain, not an artifact-shaped timestamp record. The scheduled dump, restore controller, and preflight must retain complete environment/schema/service/tool lineage and dereference the backup-readiness, baseline, and actual-recovery records independently. `verify-backups.sh` contributes only existence/reachability evidence; immutable lineage, artifact readability, restore-tool compatibility, erasure replay, and player-facing readiness require separate evidence. Player-facing readiness remains blocked until backup-under-write, inventory convergence, hardening, smoke, and controlled-reopen proof is available.

The artifact-integrity, recovery-participant, and reopen-attempt metrics in this document are target-state contracts, not evidence that the current runtime emits them. No reliable emitter for `backup_artifact_lineage_valid`, `backup_artifact_restore_readable`, `recovery_participant_convergence_state`, or `recovery_reopen_attempt_total` is currently implemented or proven. The reference Prometheus ruleset includes fail-safe missing-source alerts, but those alerts cannot identify an environment or participant after an entire family disappears and must not be interpreted as recovery state. Recovery observability remains unproved; the durable recovery controller and retained evidence records remain the readiness authority.

## Restore-Cutover Actor Contract

Before any restore-hardening observer or validator probe runs, the durable recovery controller must persist exactly one idempotent restore-cutover request for the target environment. The request contains a stable `restoreCutoverOperationId`, immutable request digest, target boundary, requested compromise/restore mode, and expected current authority context. A retry or lost response reuses that persisted request; failure to durably establish it or ambiguity about its identity keeps quarantine closed.

The recovery controller's authenticated orchestration path submits that already-persisted request to Account Service. Account executes the single idempotent signing transition: the materialization controller performs only private Secret generation and resource-version CAS, while Account validates the result, advances issuer authority, publishes public JWKS, invalidates restored Account and gameplay session authority, and records the committed outcome. This is logical application ownership, not a claim that the Secret, JWKS, durable authority, and Redis effects share one physical transaction.

The `jwt-rotation` Job/CronJob never creates or requests a second transition. It reads and reconciles the persisted request and Account outcome, runs validator-convergence probes using public JWKS, and writes only operation-bound rotation evidence. It cannot read or export private material, write `jwt-jwks`, mutate Account signing state, invalidate sessions, or request validator refresh/restart; those mutations remain with Account, the materialization controller, or the validator's owning operator/controller as defined by their respective contracts.

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
- `recovery_participant_convergence_coverage_missing{environment,participant}` – preserves the affected participant family; environment-level inventory-completeness failures use the reserved `participant="__environment__"` sentinel
- `recovery_participant_convergence_source_missing{source_family}` – global monitoring-gap signal for total disappearance of a required inventory source family; it is not readiness state for any particular environment

The `participant` metric label is a closed participant-family enum, not a service instance, plugin, tenant-defined name, workflow ID, or other free-form inventory identity. The initial allowed values are:

- `gameplay_commands`
- `tick_effects`
- `remote_followups`
- `automation_work_items`
- `external_effects`

`__environment__` is reserved solely for environment-level inventory-completeness failures. Adding another participant-family value requires an architecture and observability-contract update; exact participant or integration identities within a family remain in the durable recovery-controller inventory, retained evidence, and structured audit/log records.

The `state` label on `recovery_participant_convergence_state` is a closed enum: `blocked`, `converged`, `terminalized`, `invalidated`, or `fenced_disabled_backlog_retained`. The derived `recovery_participant_convergence_blocked` series is restricted to `state="blocked"` and is produced from the current source state, not written as independent controller state. The `source_family` label on `recovery_participant_convergence_source_missing` is also closed; the current values are `inventory_complete` and `participant_inventory`. These labels must not carry arbitrary participant, service, workflow, or inventory-source identities.

The controller owns only the source projection: it publishes each environment's required participant-family inventory and current participant state as one all-or-nothing projection. It sets the completeness marker to `0` before changing source series, reconciles the full authoritative family set by removing or explicitly tombstoning every family absent from that inventory, including stale `recovery_required_participant_inventory` and `recovery_participant_convergence_state` series, and exposes the complete current set before setting the marker to `1`; a partial projection must never carry `complete=1`. It does not independently write the derived coverage, blocked, or source-missing recordings. The `recovery_participant_convergence_coverage_missing` recording intentionally has only `environment` and `participant` labels: it identifies missing coverage and has no `state` label. The blocked recording preserves `environment`, `participant`, and its bounded `state` while the corresponding current source state is `blocked`; it clears when that source series is removed or no longer blocked, including when the source disappears. The global source-missing recording is derived separately from bounded `absent(...)` conditions and carries only `source_family`; because a missing source family has no remaining environment label, it cannot replace environment-specific source health or readiness proof. The cumulative `recovery_participant_convergence_total` event counter is audit history only and must not drive an active blocked alert.

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

Before opening production to player traffic for the first time, or reopening it after any PostgreSQL restore or rewind, preflight must consume proof that the backup pipeline is already functioning for that environment. A restore into a fresh environment boundary is subject to the same gate plus its new-boundary binding checks. The exporter records the traffic-open projection only after the durable controller finalizes the release.

Traffic-open evidence is a separate event-bound projection around the canonical recovery and backup-readiness evidence. Preflight authorizes the event from the durable recovery controller; the writer exports the checked-in immutable projection only after the controller has observed/applied the release and reached `finalized`.

This is target-state behavior. The current production preflight has no durable controller read and intentionally fails `PREFLIGHT-BACKUP-002` closed; no production traffic-open projection writer is implemented.

Canonical evidence path:

- `design/operations/deployments/production/traffic-open/<first-live|reopen>-<deployment-ref>/<deploymentEventId>.json`

Required fields:

- `schemaVersion`
- `environment`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; the controller may hold a runtime authorization before release)
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
- an isolated production-equivalent drill may run in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its controlled reopen authorizes only that isolated boundary
- the target preflight integration must identify and read the durable actual-recovery controller directly for first-live and reopen, require its state to be `ready_to_reopen` with `recoveryPurpose=actual-recovery` and event-matching `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`), and require its `targetBoundary` to equal `playerFacingTargetBoundary`; it must not accept a transient traffic-open file as authority
- `deploymentEventId` must equal the referenced preflight report so every retry, first-live attempt, or reopen has a unique immutable projection and cannot overwrite or reuse another event's evidence
- the retained projection exported after release uses `trafficOpenStatus=finalized` and must dereference the same actual-recovery record in `finalized`; `trafficOpenedAt` is required only for this form
- `restoreDrillLastSuccessAt` must be within 30 days
- `backupCoverage` must be `environment-wide-postgresql`
- the referenced recovery record must prove the exact environment-wide cold-start contract and controlled reopen path
- `backupConfidentialityEvidence` must prove the backup confidentiality invariant and, whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion evidence
- `PREFLIGHT-BACKUP-002` validates the event against the durable controller while the actual recovery is `ready_to_reopen`; `continueRecovery(operationId, expectedPhase, evidenceRef)` idempotently reconciles the internal `ready_to_reopen -> releasing -> finalized` phases, applying and observing quarantine release before permitting player traffic
- the exporter writes the checked-in traffic-open projection, including `trafficOpenedAt`, only after the controller reaches `finalized`; the projection is not a prerequisite for that same release, and a runtime authorization or partially written file is not proof that the transition completed
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

After the durable controller finalizes a `hobby-self-hosted` first-live or reopen event, the exporter records the retained traffic-open projection at:

- `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>/<deploymentEventId>.json`

Required fields:

- `schemaVersion`
- `environment`
- `eventType` (`first-live` or `reopen`)
- `trafficOpenStatus` (`finalized` in the checked-in projection; the controller may hold a runtime authorization before release)
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
- the actual-recovery controller must be `ready_to_reopen` when preflight authorizes either event, use event-matching `trafficExposure`, and name the exact `playerFacingTargetBoundary`; its idempotent release reconciliation must apply and observe quarantine release and reach `finalized` before traffic flows, after which the exporter writes both checked-in projections
- `deploymentEventId` must equal the referenced preflight report so evidence cannot be reused across retries or traffic-open events
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- `PREFLIGHT-BACKUP-003` authorizes release from the live `ready_to_reopen` controller and its immutable pre-release evidence; it must reject missing or stale compliance/controller evidence and any deployment, event, baseline-recovery, or actual-recovery lineage that does not match the current traffic-open event
- the current event's checked-in traffic-open projection is not a preflight input because it is produced only after idempotent release reconciliation reaches `finalized`
- hobby player traffic must not open when this evidence is missing, stale, mismatched, or bound to a failed preflight run
- the traffic-open projection must be exported or refreshed after the controller finalizes every first-live or reopen event, even when the referenced compliance record did not change, so the retained projection remains bound to the current finalized deployment or recovery lineage and cannot be reused for a later event

## Canonical Recovery Record

Every production-equivalent drill and actual player-facing restore has one durable recovery-controller state machine. The checked-in records below are immutable projections exported only after that controller reaches `finalized`; they are not runtime authority or release-transaction inputs. For an actual recovery, preflight reads the controller in `ready_to_reopen`, and the controller owns the release reconciliation:

- `production`: `design/operations/deployments/production/recovery/<recovery-ref>.json`
- `staging`: `design/operations/deployments/staging/recovery/<recovery-ref>.json`
- `hobby-self-hosted`: `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json`

Required top-level fields:

- `schemaVersion`
- `environment`
- `recoveryRef`
- `operationId`
- `recoveryStatus` (`finalized` in the checked-in projection; the runtime controller also uses `collecting`, `ready_to_reopen`, and `releasing`)
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
- `jwtHardening` includes the rotation job reference, the stable `restoreCutoverOperationId`, the immutable restore-cutover request digest, resulting key IDs, the recorded compromise/reset `issuerAuthorityGeneration` advance, explicitly distinguished from per-token `tokenGeneration` and any other authority generation, and proof that issued-token registry snapshots whose `issuerAuthorityGeneration` is older than that recorded compromise/reset generation are rejected. It also includes bounded physical registry/session cleanup status and validator-convergence evidence; cleanup is required retained evidence but is not a wildcard-scan revocation authority
- `validatorInventoryRef` points to an authoritative, complete, reachable inventory. Every validator must have a safe converged result and must receive public JWKS only; missing, unknown, unreachable, or private-key-access results fail recovery
- Post-restore JWT rotation preserves Account Service custody of non-exportable private signing material and JWKS publication authority. Rotation Jobs observe the Account-owned generation transition and published public JWKS but do not request the transition, write `jwt-jwks`, read or export private keys, or mutate Account signing state; recovery evidence must never contain private signer material
- `databaseCredentialRotation` includes rotation job reference, affected Secret refs, and rollout-restart completion evidence
- `certificateReissuance` includes workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation` includes one result per credential class with `validationMethod`, `validatedAt`, `validatedBy`, `observedValue`, isolation assertion, and immutable evidence ref. `observedValue` is explicitly non-secret and is limited to a resource ID, certificate/key fingerprint, or redacted presence indicator; it must never contain a password, token, private key, raw secret material, or an unredacted credential-bearing connection string
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed, and whether each class used `lastProvisionedAt` or `lastRotationAt`
- for a staging restore of production-origin data, `sanitizationEvidenceRef` identifies an immutable pre-release `recovery-sanitization-evidence/v1` artifact whose `recoveryRef`, `operationId`, `deploymentEventId`, and `backupArtifactDigest` match the durable controller and restored artifact. The finalized recovery projection references that same artifact; it is not used circularly as its own pre-release evidence
- `recoveryControllerLineage` identifies the durable controller state, environment-wide scope, linked artifact and participant lineage, pre-release `ready_to_reopen` approval, and post-release `finalized` state when this projection is exported
- `backupConfidentialityEvidence` uses `status=pass`, `transport=encrypted`, and `storage=encrypted` and proves environment-scoped least-privilege access and audit plus retention/secure deletion. Whenever production-origin data is exercised outside production, it also proves quarantine, sanitization, validation, and deletion
- `backupArtifactLineage` binds the environment-wide PostgreSQL artifact to its immutable digest, database identity, snapshot identity and time, schema/migration lineage, service digests, object-storage identity, and the same `artifactErasureHighWater`; it proves that the high-water was captured inside the same database snapshot and identifies the greatest authoritative erasure-ledger sequence already included. The artifact bytes and digest are stored first, one immutable manifest binds that digest to the snapshot and high-water second, and an atomic or compare-and-set ready-publication record makes that pair eligible for recovery last. Missing, mutable, duplicate, partially published, or non-matching objects fail validation
- `artifactErasureHighWater` is the snapshot-bound source high-water, `initialCatchupHighWater` is captured immutably when recovery catch-up starts, and `restoreHighWater` is captured immutably by the bounded final cutover as the readiness boundary. All three come from the same authoritative ledger and must satisfy `restoreHighWater >= initialCatchupHighWater >= artifactErasureHighWater`; none may be inferred from restored PostgreSQL
- `erasureReplay` identifies the authoritative ledger, exclusive start (`artifactErasureHighWater`), initial catch-up boundary (`initialCatchupHighWater`), inclusive final end (`restoreHighWater`), replayed-through sequence, gap-free completion evidence, bounded final-cutover evidence, and the installed online-consumer cursor. The interval must contain every erasure event in order, without gaps or unknown entries, and the final cutover must prove atomic handoff to normal processing before the controller reaches `ready_to_reopen`
- `coordinationRecoveryEvidence` uses `mode=cold_start_restore`, `coordinationRedis=empty-before-rebuild`, `credentialBinding=rotated-or-rebound`, `targetEnvironmentBound=true`, `snapshotCredentialsRejected=true`, and `regionEpochFences=advanced-or-recreated` to prove an empty Coordination Redis keyspace and fresh target-environment credentials before rebuild plus environment-wide gameplay-region epoch/fence advancement or recreation
- `recoveryParticipantInventoryRef` and `externalEffectInventoryRef` each point to authoritative, complete, reachable inventories. `durableParticipantConvergence` and `externalEffectReconciliation` must contain one safe disposition for every declared and enabled entry: `converged`, `terminalized`, `invalidated`, or `fenced_disabled_backlog_retained`; missing, unknown, unreachable, or unsafe entries fail the gate
- `sessionRecovery` proves environment-wide gameplay and Account session invalidation and must use `gameSessionHandling=invalidated` and `authSessionHandling=invalidated`; fresh sessions may be issued only after the reopen gate

Validation rules:

- quarantine remains in place while the controller is `collecting`; once every required pre-release control group passes, the bounded final erasure cutover and online-consumer handoff succeed, and `reopenApprovedBy` is recorded, the controller records `readyToReopenAt` and advances its durable state to `ready_to_reopen`
- the one authorized `continueRecovery(operationId, expectedPhase, evidenceRef)` call uses `expectedPhase=ready_to_reopen`; callers retry the same tuple and never submit public `expectedPhase=releasing`. It validates immutable evidence and durably claims the transition into internal `releasing`. A retryable failure records the current phase and an attempt outcome rather than a terminal operation result; retries resume observation or the idempotent release step without applying it twice. Mismatched phase or evidence fails without mutation, concurrent identical calls observe the same attempt or final result, and exactly one terminal continuation result is stored per `operationId`. Any failed or ambiguous apply remains fail-closed in `releasing` and cannot produce `finalized`
- after the controller applies and observes the release, it advances its durable state to `finalized`; only then may actual player traffic flow, and only then may the checked-in recovery and traffic-open projections be exported
- a `production-equivalent-drill` uses `trafficExposure=isolated-drill`; its controlled reopen authorizes only the isolated test boundary and cannot authorize production traffic
- an `actual-recovery` that will open player traffic uses event-matching `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`) and is bound to that exact target boundary
- an actual-recovery record that opens player traffic must bind `deploymentEventId` and `preflightReportPath` to the same event-scoped preflight report as the traffic-open projection; production-equivalent drills omit `deploymentEventId` and cannot authorize player traffic
- `coordinationRecoveryMode` must be `cold_start_restore` for player-facing recovery; `scoped_reset_restore` experiments remain quarantined and cannot satisfy this record
- every declared and enabled participant named by `recoveryParticipantInventoryRef` must have a safe disposition; a durable fenced backlog may survive reopen only when the participant remains disabled and its owning recovery contract defines the later operator action
- `readyToReopenAt` must be later than restore-safe-mode entry, coordination recovery, erasure replay completion, hardening, external-credential validation, secret-compliance refresh, and smoke-check completion times; `quarantineReleasedAt` and `finalizedAt` must be later than `readyToReopenAt`, and exported projections must carry the controller's finalized release identity
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
