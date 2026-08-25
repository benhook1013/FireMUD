# FireMUD Backup Recovery Evidence and Compliance

This document defines the machine-checkable evidence, metrics, and compliance records required to prove that FireMUD backups and restore workflows are healthy enough for player-facing operation.

The canonical P0 decisions are [measured online-backup RPO](./decisions/adr-0153-measured-online-backup-rpo-and-future-pitr-trigger.md), [automated recovery proof and differentiated traffic-open gates](./decisions/adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md), and [event-classified post-restore trust reset](./decisions/adr-0155-automated-event-classified-post-restore-trust-reset.md). This document owns retained evidence shape and compliance checks; live recovery and release authority remain with the durable recovery controller.

## Implementation Notes

Backup-pause metrics are maintenance/reset observability, not player-facing PostgreSQL-recovery proof. They may retain bounded `tenant`/`game-instance`/`region` scope labels and incomplete-scope migration signals for those maintenance workflows, but accepted recovery readiness is proved by one environment-wide recovery-controller lineage. Exact tenant, game-instance, and region identities belong in retained maintenance evidence and control-plane reads, not in the accepted backup-recovery gate.

Backup readiness is an evidence chain, not an artifact-shaped timestamp record. The scheduled dump, restore controller, and preflight must retain complete environment/schema/service/tool lineage and dereference the backup-readiness, baseline, and actual-recovery records independently. `verify-backups.sh` contributes only existence/reachability evidence; immutable lineage, artifact readability, restore-tool compatibility, erasure replay, and player-facing readiness require separate evidence. Player-facing readiness remains blocked until backup-under-write, inventory convergence, hardening, smoke, and controlled-reopen proof is available. Target state requires scheduled isolated drills and automated evidence production; the current implementation does not provide that end-to-end path, so routine proof cannot claim it or depend on an operator transcribing timestamps or result fields. No resumable recovery controller or crash-recoverable traffic-release state machine exists in the current implementation, so hosted player-facing restore readiness remains blocked. The compact verified-point golden helper is non-authorizing schema/digest proof only; protected readiness remains fail-closed until owner-authoritative database, artifact, lineage, and restore-tool bindings plus successful isolated restoration of the same artifact are available.

The artifact-integrity, recovery-participant, and reopen-attempt metrics in this document are target-state contracts, not evidence that the current runtime emits them. No reliable emitter for `backup_artifact_lineage_valid`, `backup_artifact_restore_readable`, `recovery_participant_convergence_state`, or `recovery_reopen_attempt_total` is currently implemented or proven. The reference Prometheus ruleset includes fail-safe missing-source alerts, but those alerts cannot identify an environment or participant after an entire family disappears and must not be interpreted as recovery state; partial source loss must remain a blocked readiness condition rather than becoming an apparent pass. Recovery observability remains unproved; the durable recovery controller and retained evidence records remain the readiness authority.

Partial participant-series disappearance is a participant-specific coverage failure, not total family absence. The durable source projection must retain the complete required inventory and coverage state; if that projection cannot be retained, the controller must write an explicit environment/participant readiness blocker and keep quarantine closed. Only complete disappearance of a required source family may use the global `recovery_participant_convergence_source_missing{source_family}` monitoring-gap signal, and that signal must never replace the participant-specific fail-closed blocker.

## Restore-Cutover Evidence Boundary

The restore mode, reopen, and replay boundary is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). This document retains the evidence consequence: before hardening observers or validator probes run, the recovery controller must have one durable restore-cutover identity, and ambiguity keeps quarantine closed.

JWT cleanup, custody, and controller semantics are canonical in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). Evidence records the Account-owned authority/delegation result separately from the Game Session-owned gameplay-binding/session result. Pre-apply trusted bootstrap evidence is separate from post-apply live signer, public-JWKS, and validator convergence evidence. The JWT rotation-evidence workload is observation-only: it observes the operation and writes operation-bound evidence, but does not reconcile it or access private material.

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

Every production promotion and every production first-live/reopen event must consume a current environment-bound freshness proof for the newest verified restorable PostgreSQL point. Releases classified as `roll-forward-only`, releases whose recovery-compatibility result requires a new drill, and production first-live/reopen baselines must also include a full backup-readiness record at:

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
- `newestVerifiedRestorablePointAt`
- `newestVerifiedRestorablePointRef`
- `newestVerifiedRestorablePointDigest`
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

- `newestVerifiedRestorablePointAt` is the immutable artifact capture/snapshot time and must be within the 15-minute hosted RPO objective at production preflight; later verification time, schedule start, or object existence does not satisfy this field
- `newestVerifiedRestorablePointRef` resolves to one immutable `verified-restorable-point/v1` record under `design/operations/deployments/production/verified-restorable-points/`. `newestVerifiedRestorablePointDigest` must exactly equal that record's `recordDigest`, which is the SHA-256 digest of the record's canonical UTF-8 bytes, encoded as `sha256:` plus 64 lowercase hexadecimal characters. A digest-looking value or mutable reference is not evidence.
- At `assessedAt`, an eligible point is a canonical, digest-valid, production-bound record whose artifact, database, lineage, and restore-tool bindings validate, whose `backupArtifact.snapshotAt` is no later than `assessedAt` (the assessment/decision time), and whose `verification.verifiedAt` is no later than the assessment. A future-dated artifact capture is therefore ineligible and fails closed rather than being selected as the newest point. The selected record must have the greatest eligible `backupArtifact.snapshotAt` among every eligible record in the immutable verified-point set. More than one eligible record at that greatest snapshot timestamp is ambiguous and fails closed; operators must reconcile the producer lineage rather than choose by path, verification time, or digest ordering.
- `backupLastSuccessAt` records pipeline execution separately and cannot substitute for the verified restorable point
- `backupVerifyLastSuccessAt` is the selected verified-restorable-point record's `verification.verifiedAt` timestamp and must be within 36 hours
- `restoreDrillLastSuccessAt` within 30 days; a waiver may postpone an isolated drill or salvage exercise but cannot authorize player-facing first-live, post-rewind reopen, or `roll-forward-only` promotion without the required proof

### Canonical Verified Restorable Point Record

The verified-point producer and every readiness consumer share one owner-defined `verified-restorable-point/v1` record. The record has exactly these fields; unknown fields, missing fields, duplicate JSON members, and alternate local schemas are invalid:

```json
{
  "schemaVersion": "verified-restorable-point/v1",
  "environment": "production",
  "databaseIdentity": {
    "clusterIdentity": "<target-cluster-identity>",
    "databaseName": "<database-name>"
  },
  "backupArtifact": {
    "artifactRef": "<immutable-artifact-ref>",
    "artifactIdentity": "<immutable-artifact-identity>",
    "artifactDigest": "sha256:<64-lowercase-hex>",
    "lineageRef": "<immutable-lineage-ref>",
    "snapshotAt": "<RFC3339-UTC-artifact-capture-timestamp>"
  },
  "verification": {
    "operationId": "<verification-operation-identity>",
    "verifiedAt": "<RFC3339-UTC-timestamp>",
    "restoreToolIdentity": {
      "name": "<supported-restore-tool-name>",
      "version": "<supported-restore-tool-version>",
      "digest": "sha256:<64-lowercase-hex>"
    }
  },
  "recordDigest": "sha256:<64-lowercase-hex>"
}
```

The producer serializes the complete record as RFC 8785 JSON Canonicalization Scheme bytes encoded as UTF-8: object-member ordering and string encoding are canonical, no producer whitespace is present, and the schema's scalar values are non-empty ASCII strings. For the hash preimage, omit only the top-level `recordDigest` member; include every other member and nested object. The producer computes SHA-256 over those bytes and writes the resulting lowercase `sha256:` digest into `recordDigest`. It publishes the record and its immutable storage binding before publishing the readiness record that references it. Within this exact `verified-restorable-point/v1` record, `verification.restoreToolIdentity` identifies the exact restore consumer, which for this initial hosted logical lane is `psql` with the exact name, version, and tool digest accepted by the recovery-tool owner. The outer backup-readiness/recovery binding carries `backupToolDigest`, which identifies the `pg_dump` producer for the scheduled hosted lane (`pg_dump -Fp` followed by gzip, producing an immutable `.sql.gz` artifact); `backupToolDigest` is not a field in this inner record. This does not authorize an unregistered tool. The local ad hoc custom-format `.dump`/`pg_restore` pair is not the hosted verified-point artifact/tool pair.

The consumer dereferences the immutable record, rejects duplicate members and any schema deviation, recomputes the same RFC 8785/UTF-8 preimage and digest, and exact-matches `recordDigest` to `newestVerifiedRestorablePointDigest`. It also exact-matches `environment`, `backupArtifact.snapshotAt` to `newestVerifiedRestorablePointAt`, `verification.verifiedAt` to `backupVerifyLastSuccessAt`, and `backupArtifact.artifactRef` to `backupArtifactRef`; verification must not predate the captured artifact. The record itself binds the target database identity, artifact identity/digest/lineage and capture time, verification operation/time, and supported restore-tool identity. The consumer must verify the record's target-environment binding and database/artifact/tool identities against the authoritative environment and backup lineage, not merely trust the fields' presence. No timestamp, object-store key, Git path, or mutable pointer substitutes for this record.

The shared producer/consumer proof is one focused golden vector in the existing production preflight contract test. It asserts the exact canonical bytes and expected digest, accepts the unchanged record through the consumer helper, and rejects a changed artifact digest, changed snapshot time, changed verification time, duplicate member, or digest mismatch. This is non-authorizing schema/digest proof for the shared owner record, not a claim that end-to-end backup automation or authoritative identity binding is implemented; protected readiness remains fail-closed until the owner-authoritative database, artifact, lineage, and restore-tool bindings and successful isolated restoration of the same artifact are proved. The current production path still fails closed where the durable recovery controller is unavailable. Select and report this check under the shared [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker, not in this normative evidence document.

Evidence reuse and invalidation:

- rollback-compatible steady-state releases use the compact recovery-compatibility result below and need not duplicate this full record when reuse is valid
- every promotion still consumes the current freshness reference/digest; a compact compatibility result may reference that live freshness proof without copying the full selected-release backup-readiness record
- an invalidating recovery-contract change requires a new production-equivalent drill even when the 30-day window has not expired
- a `roll-forward-only` release never reuses only the cadence record; its source artifact comes from the current production database lineage under representative writes and is restored using candidate recovery tooling, exact candidate service digests, the candidate migration path, and candidate config/bindings
- first-live and reopen events require environment-specific proof for the boundary being opened
- target state schedules isolated drills and automates their evidence production; this is not implemented end-to-end in the current baseline and cannot be presented as current proof

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
- the freshness reference and digest must bind to the target production environment and the newest artifact that passed completeness, readability, lineage, and supported restore-tool checks; an upload key or existence check alone is not qualifying evidence

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
- `newestVerifiedRestorablePointRef`
- `newestVerifiedRestorablePointDigest`
- `newestVerifiedRestorablePointAt`
- `backupReadinessRef` when `newDrillRequired=true`

The evaluator compares backup/restore tool compatibility, database and migration restore compatibility, durable workflow and reconciliation semantics, Coordination Redis recovery, enabled participant inventory, post-restore hardening, secret/binding contracts, and environment binding. It also dereferences the current environment-bound freshness reference and exact digest for every promotion. Restore-compatible additive migrations and routine secret-value rotation do not require a new drill when their recovery, authority, delivery, and hardening contracts are unchanged. A semantic or contract change returns `drill_required`; a `roll-forward-only` release always sets `newDrillRequired=true`. A current verified point is required even when the compact result reuses an otherwise compatible drill.

## Production Traffic-Open Backup Evidence

Before opening production to player traffic for the first time, or reopening it after any PostgreSQL restore or rewind, preflight must consume proof that the backup pipeline is already functioning for that environment. A restore into a fresh environment boundary is subject to the same gate plus its new-boundary binding checks. The durable recovery controller owns the operation lifecycle and release boundary; the exporter records the traffic-open projection only after that controller finalizes the release.

Traffic-open evidence has two distinct forms. The operation-bound pre-release evidence record identified by `evidenceRef` is immutable evidence for the exact recovery operation, scope, and event and is consumed by the controller's owner-defined continuation/release controls. The checked-in traffic-open record below is a separate immutable post-finalization projection. It is retained evidence, not the pre-release `evidenceRef` record and not an input to the release transaction that produced it. A later preflight may consume retained finalized recovery projections as authorization evidence, including by dereferencing and validating `restoreRecoveryRecordRef` and `baselineRecoveryRecordRef`; that later consumption does not make either projection an input to the earlier release. The exact operation-bound `evidenceRef` consumed by the controller must be traceable from the finalized projection's `evidenceRefs[]`, together with any lineage required by the operation-bound record.

At the owner's `ready_to_reopen` boundary, the operation-bound pre-release evidence record uses `schemaVersion=traffic-open-pre-release-evidence/v1` plus `projectionSchemaVersion=traffic-open-record/v1` and binds the exact operation/event tuple that the exporter must preserve in the finalized projection: `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure`. The finalized projection must exact-match that tuple and uses `schemaVersion=traffic-open-record/v1` only; it does not carry `projectionSchemaVersion`. The consumed operation-bound `evidenceRef` must appear in the finalized projection's `evidenceRefs[]`. The recovery owner defines the continuation call shape; the projection is written only after finalization and is not consumed as release authority.

Each production and `hobby-self-hosted` `traffic-open-record/v1` projection computes its own `contentDigest` from its own complete schema-defined canonical JSON payload, excluding only `contentDigest`. Before digesting or comparing payloads, every repeated field that the schema declares order-insensitive, including `evidenceRefs[]`, is canonicalized as a multiset: canonicalize each element with RFC 8785 JSON Canonicalization Scheme, sort the resulting UTF-8 canonical element bytes lexicographically, and preserve duplicate occurrences unless the schema rejects them. Repeated fields whose schema order is significant remain ordered. The projections share only these canonicalization, encoding, and comparison rules: the payload and resulting digest are not shared between production and hobby projections or between separate `first-live` and `reopen` events. The same normalization applies to the initial event and every retry before equality is evaluated. The digest is computed from the resulting UTF-8 canonical payload bytes using SHA-256; it is encoded as `sha256:` followed by 64 lowercase hexadecimal characters. An exporter recomputes the applicable projection digest for a retry and may reuse an existing event path only when the exact finalized event tuple and normalized `contentDigest` match.

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
- `newestVerifiedRestorablePointAt`
- `newestVerifiedRestorablePointRef`
- `newestVerifiedRestorablePointDigest`
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
- `contentDigest`
- `evidenceRefs[]`

Validation rules:

- backup and verification evidence must bind to the production source lineage; preflight must dereference `backupReadinessRef`, validate the backup-readiness artifact's own `restoreRecoveryRecordRef`, and independently dereference `baselineRecoveryRecordRef`. Both referenced records must be finalized isolated drills compatible with the boundary being opened
- every first-live and reopen event must dereference the current environment-bound `newestVerifiedRestorablePointRef`, exact-match `newestVerifiedRestorablePointDigest`, and prove `newestVerifiedRestorablePointAt` is no older than 15 minutes at the event; a backup upload or object-store key existence check alone is insufficient
- at `ready_to_reopen`, and again immediately before the durable controller enters `finalized` and releases exposure, the controller must independently reread the current environment-bound newest verified point, dereference its immutable record, recompute and exact-match its digest, revalidate its environment/artifact/database/lineage/restore-tool bindings, and check the 15-minute freshness bound. Apply-time preflight evidence is an input to these checks, never final exposure authority; a stale, missing, mismatched, or failed check keeps traffic quarantined and requires a new event-scoped preflight event/report before retry
- an isolated production-equivalent drill may run in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its evidence is bound only to that isolated boundary
- the durable actual-recovery controller owns the pre-release and release lifecycle for first-live and reopen. Any preflight consumer must compare the immutable operation-bound evidence and event-matching controller lineage, and must not accept a transient traffic-open file as authority
- `deploymentEventId` must equal the referenced preflight report so every retry, first-live attempt, or reopen has a unique immutable projection and cannot overwrite or reuse another event's evidence
- the finalized projection must retain `schemaVersion=traffic-open-record/v1` only, and its `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure` must exact-match the consumed operation-bound evidence and finalized controller lineage; it must include the exact operation-bound `evidenceRef` in `evidenceRefs[]`; where the operation-bound record requires lineage, that lineage must remain traceable there. A missing, mutable, or reissued projection is a later evidence-integrity failure, not a release input
- the retained projection exported after release uses `trafficOpenStatus=finalized` and must dereference the same actual-recovery record in `finalized`; `trafficOpenedAt` is required only for this form
- `restoreDrillLastSuccessAt` must be within 30 days
- `backupCoverage` must be `environment-wide-postgresql`
- the referenced recovery record must prove the exact environment-wide cold-start contract and controlled reopen path
- `backupConfidentialityEvidence` must prove the backup confidentiality invariant and, whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion evidence
- `PREFLIGHT-BACKUP-002` validates the backup/recovery lineage and event identity against immutable controller-owned evidence; the durable recovery controller remains the sole continuation and release authority described in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Checked-in projections are retained evidence and never replace live controller authority.
- the exporter writes the checked-in traffic-open projection, including `trafficOpenedAt`, only after the controller reaches `finalized`; the projection is not a prerequisite for that same release, and a runtime authorization or partially written file is not proof that the transition completed
- the exporter must create one new immutable traffic-open projection per `deploymentEventId` after the controller finalizes every first-live or reopen event; an export retry may reuse the event path only when the existing payload exactly matches the finalized event tuple and canonical `contentDigest`. It must not mutate or refresh an existing projection, and any mismatch or attempt to reuse a projection for another event is an evidence-integrity failure; a finalized projection cannot be reused for a later event
- the canonical gate for this artifact is the deployment preflight contract in `system-architecture-deploy-preflight-policy.md` (`PREFLIGHT-BACKUP-002`), and the deployment sequencing that consumes it is defined in `system-architecture-deployment-runbook.md`

## Hobby Backup Compliance Evidence

`hobby-self-hosted` environments must maintain a versioned backup-compliance record at:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml`

Required fields:

- `schemaVersion`
- `environment`
- `status` (`verified` or `recovery-unverified`)
- `lastSuccessfulBackupAt`
- `lastSuccessfulRestoreDrillAt` when `status=verified`; it may be omitted or `null` when `status=recovery-unverified`
- `lastRestoreDrillAt` when a drill has been attempted; it may be omitted or `null` before any attempt and never substitutes for a successful drill
- `retentionDailyPoints`
- `backupTooling`
- `recoveryContractFingerprint`
- `lastSuccessfulRecoveryRecordRef` when `status=verified`
- `recoveryUnverifiedAcknowledgement` when `status=recovery-unverified`
- `evidenceRefs[]`

Every record must prove a successful logical backup no more than 24 hours old and `retentionDailyPoints >= 7`; a schedule declaration without the current successful-backup timestamp and retained-point evidence is insufficient. `recovery-unverified` records include the operator acknowledgement, timestamp, reason, and exact declared `recoveryContractFingerprint`; they may omit or set `lastSuccessfulRestoreDrillAt` to `null`, make no restore-readiness claim, and must not include or claim `lastSuccessfulRecoveryRecordRef`. Verified status additionally requires a non-null successful restore-drill timestamp no more than 30 days old, the supported automated local rehearsal, current evidence, and `lastSuccessfulRecoveryRecordRef`. The current executable intentionally fails `PREFLIGHT-BACKUP-003` closed before consuming this target schema because the durable controller read and hobby compliance validator are not implemented; checked-in shape alone is not validation. Missing or unavailable automation cannot be presented as verified proof. Restore hardening for hobby/self-hosted always fails closed for post-restore player-traffic reopen if verified actual-recovery evidence is missing, stale, or below baseline.

## Hobby Traffic-Open Evidence

The `hobby-self-hosted` operation-bound pre-release record reuses the complete production `traffic-open-pre-release-evidence/v1` schema defined above, including `schemaVersion=traffic-open-pre-release-evidence/v1`, `projectionSchemaVersion=traffic-open-record/v1`, `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure`; the consumed `evidenceRef` must appear in the finalized projection's `evidenceRefs[]`. After the durable controller finalizes a `hobby-self-hosted` first-live or reopen event, the exporter records the retained traffic-open projection at:

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
- `recoveryPosture` (`verified` or `recovery-unverified`)
- `baselineRecoveryRecordRef` when `recoveryPosture=verified`
- `lastSuccessfulRecoveryRecordRef` when `recoveryPosture=verified`
- `recoveryContractFingerprint`
- `recoveryUnverifiedAcknowledgement` when a first-live event uses `recovery-unverified`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen)
- `playerFacingTargetBoundary`
- `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`, matching `eventType`)
- `preflightReportPath`
- `trafficOpenedAt` when `trafficOpenStatus=finalized`
- `contentDigest`
- `evidenceRefs[]`

Validation rules:

- a verified first-live event requires `backupComplianceRef` to dereference to the current `status=verified` compliance record; that record's `lastSuccessfulRecoveryRecordRef`, the traffic-open `baselineRecoveryRecordRef`, and the traffic-open `lastSuccessfulRecoveryRecordRef` must all resolve to the same current verified finalized recovery record and its automated local rehearsal proving the environment-wide `cold_start_restore` contract
- the traffic-open `recoveryContractFingerprint` and the compliance record's fingerprint must exact-match the `recoveryContractFingerprint` value in that same current verified recovery record; stale, split, or mismatched references or fingerprints fail closed
- a first-live event may instead use `recoveryPosture=recovery-unverified` with explicit acknowledgement and a clear no-recovery-promise diagnostic; `backupComplianceRef` must dereference to the current `status=recovery-unverified` compliance record whose `recoveryContractFingerprint` exact-matches the traffic-open fingerprint, the event must omit `baselineRecoveryRecordRef` and `lastSuccessfulRecoveryRecordRef`, and it must not claim verified status
- an unverified first-live waiver still requires `PREFLIGHT-BACKUP-003=pass` at the actual-recovery controller's `phase=ready_to_reopen` boundary; the pre-release gate must not require `phase=finalized`, `status=SUCCEEDED`, or quarantine-release postconditions. Those finalized-release and `SUCCEEDED`/quarantine-release postconditions are reserved for validating the post-release traffic-open projection, and the waiver cannot be used for any reopen event
- every reopen event requires verified posture plus the actual recovery record for that restore; the first-live exception never applies after a rewind
- the actual-recovery controller lineage and finalized projection must use event-matching `trafficExposure` and name the exact `playerFacingTargetBoundary`; after the controller reaches its owner-defined finalized state, the exporter writes the checked-in projections
- `deploymentEventId` must equal the referenced preflight report so evidence cannot be reused across retries or traffic-open events
- the finalized projection must retain `schemaVersion=traffic-open-record/v1` only, and its `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure` must exact-match the consumed operation-bound evidence and finalized controller lineage; it must include the exact operation-bound `evidenceRef` in `evidenceRefs[]`; where the operation-bound record requires lineage, that lineage must remain traceable there. A missing, mutable, or reissued projection is a later evidence-integrity failure, not a release input
- `preflightReportPath` must show `PREFLIGHT-BACKUP-003=pass`
- `PREFLIGHT-BACKUP-003` validates immutable pre-release evidence, compliance lineage, and the event-matching actual-recovery controller reference; it does not perform or authorize controller continuation or release. The preflight result must reject missing or stale compliance/controller evidence and any deployment, event, baseline-recovery, or actual-recovery lineage that does not match the current traffic-open event
- the current event's checked-in traffic-open projection is not a preflight input because it is produced only after the controller records its finalized state
- hobby player traffic must not open when this evidence is missing, stale, mismatched, or bound to a failed preflight run
- the exporter must create one new immutable traffic-open projection per `deploymentEventId` after the controller finalizes every first-live or reopen event, even when the referenced compliance record did not change; an export retry may reuse the event path only when the existing payload exactly matches the finalized event tuple and canonical `contentDigest`. It must not mutate or refresh an existing projection, and any mismatch or attempt to reuse a projection for another event is an evidence-integrity failure; a finalized projection cannot be reused for a later event

## Canonical Recovery Record

The recovery controller lifecycle and fixed replay boundary are canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). The operation-bound pre-release evidence record identified by `evidenceRef` is distinct from the checked-in records defined below and uses its own `schemaVersion=recovery-pre-release-evidence/v1`; it carries `projectionSchemaVersion=recovery-record/v1` for the finalized projection. Those checked-in records are immutable evidence projections exported only after the controller's owner-defined finalization, use `schemaVersion=recovery-record/v1`, and are not runtime authority or inputs to the release transaction that produced them. A later preflight may consume these finalized projections as retained evidence for authorization, including by dereferencing and validating `restoreRecoveryRecordRef` and `baselineRecoveryRecordRef`; that later use is not circular release input. This section owns their evidence schema, lineage, participant dispositions, hardening results, and compliance fields.

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
- `recoveryPointApprovedBy` and the displayed effective data-loss window for an actual rewind, or a reference to a separately accepted automatic-DR policy
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
- `credentialApplicability` is an object keyed by exactly the closed nine-class credential universe: `jwt-signing-keys-jwks`, `postgres-application-credentials`, `workload-leaf`, `bridge-leaf`, `operator-leaf`, `backup-storage`, `asset-storage`, `outbound-comms`, and `operator-credentials`. The five internal classes (`jwt-signing-keys-jwks`, `postgres-application-credentials`, `workload-leaf`, `bridge-leaf`, and `operator-leaf`) are always `applicable`; production `backup-storage` is also always `applicable` because production requires that environment binding. Only `asset-storage`, `outbound-comms`, and `operator-credentials` may be `not_applicable` in a production recovery record. Missing, unknown, or malformed class or value fails closed
- `credentialDispositions` is an object keyed by exactly the subset of `credentialApplicability` whose value is `applicable`; each value is exactly one of `rotated`, `reissued`, `rebound`, or `verified_not_restored`. A non-applicable class is omitted and must not claim a disposition
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
- `credentialApplicability` is the complete, exact-key classification for the closed internal and external credential universes. The five internal classes and production `backup-storage` are always `applicable`; only `asset-storage`, `outbound-comms`, and `operator-credentials` may be `not_applicable`. `applicable` requires one valid `credentialDispositions` value and the corresponding hardening or validation evidence; `not_applicable` requires no disposition and must not carry applicable/pass evidence. Applicability is an environment binding, not a claim that absent credentials were safely restored
- `credentialDispositions` records one event-classified disposition for each applicable credential/certificate class. `verified_not_restored` is allowed only when live evidence proves that the current trust material remained outside the restore artifact and still matches the target binding; fresh boundaries, restored, or unprovable trust require `rotated` or `reissued`, or a `rebound` disposition only when the trust boundary was safely re-established without reusing unsafe restored material and that binding and material lineage are proved. Compromise scope cannot use `verified_not_restored`.
- `jwtHardening` records the observation-only rotation-evidence workload reference, disposition, stable `restoreCutoverOperationId`, immutable restore-cutover request digest, explicit `compromiseClassified` discriminator, and disposition-specific validator evidence. For ordinary `rotated`, `reissued`, and applicable `rebound` replacement paths, `compromiseClassified=false` and exact `replacementEvidence` fields `{oldKid, candidateKid, oldKidRejected, candidateKidAccepted, validatorEvidenceRef}` are required: IDs are distinct, both booleans are true, the reference matches `validatorConvergenceEvidence`, the candidate is present in `resultingKeyIds`, and the old ID is absent. This proof is prohibited for compromise-classified and `verified_not_restored` branches. For a confirmed or suspected compromise hard cutover, `compromiseClassified=true` and the exact compromised/candidate key-ID and lowercase `sha256:<64 hex>` public-key-fingerprint pairings are required in the aggregate and validator evidence; compromise proof remains fail-closed. For `verified_not_restored`, it records current Account signing-authority/JWKS binding, current issuer generation and keyset integrity, validator acceptance of the unchanged current set, and the universal Account/Game Session invalidation results; it does not require a replacement `kid`, rejection of that still-current `kid`, or an issuer-generation advance solely as credential-rotation evidence. It also records bounded registry/session cleanup status and must contain no private signer material. The exact JWT cleanup, custody, controller-authority, private-material operation, and ordering semantics are defined by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative); this section retains only the returned evidence fields and observed proofs.
- `validatorInventoryRef` points to an authoritative, complete, reachable inventory. Every validator must have a safe converged result and must receive public JWKS only; missing, unknown, unreachable, or private-key-access results fail recovery
- JWT hardening evidence is non-secret and records Account/validator convergence results only; it does not define custody, controller authority, or private-material operations. Those semantics remain owned by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative), and recovery evidence must never contain private signer material
- `databaseCredentialRotation` includes the event-classified disposition, rotation job reference when applicable, affected Secret refs, durable operation identity, phase persisted before password mutation, crash-recovery or rollback outcome across database, Secret, and every authoritative consumer, and rollout/reload completion evidence proving all consumers are healthy and using the expected credential lineage before `rotated` or `rebound` is recorded
- `certificateReissuance` includes the event-classified disposition, workload, bridge, and operator leaf identity evidence plus peer-convergence evidence
- `externalCredentialValidation.records` retains exactly one record for each class in the closed external universe. An `applicable` class uses exactly `status`, `evidenceRef`, `isolationAssertion`, `validationMethod`, `validatedAt`, `validatedBy`, and non-secret `observedValue`, with `status=pass`; a `not_applicable` class uses exactly `status=not_applicable`, `reason=credential-class-not-present`, and a non-empty immutable `evidenceRef`, with no validation fields or observed credential detail. A record status must match `credentialApplicability`, and any disposition or pass evidence for a non-applicable class fails closed. `observedValue` is explicitly non-secret and is limited to a resource ID, certificate/key fingerprint, or redacted presence indicator; it must never contain a password, token, private key, raw secret material, or an unredacted credential-bearing connection string
- `secretComplianceRefresh` references the refreshed `design/operations/secret-compliance/<environment>.yaml` record, the immutable evidence payload updated by restore hardening, the credential classes refreshed using canonical recovery keys `backup-storage` and `asset-storage`, and a per-class `freshness` object containing `lineage`, `field`, `value`, `previousField`, and `previousValue`. The recovery-to-compliance projector and owner handoff apply the fixed, non-caller-selectable namespace mapping `backup-storage` -> secret-compliance `backup-object-store-credentials` and, when external asset storage is applicable, `asset-storage` -> secret-compliance `asset-store-credentials`; recovery `credentialClasses`/`freshness` retain the recovery keys, while the compliance record and evidence payload retain their compliance keys. An unmapped class or a recovery/compliance cross-namespace key fails closed. New lineage/first issuance uses `lineage=new` with either `rotated` or `reissued`, `lastProvisionedAt`, and no previous pair; existing-lineage rotated or reissued replacement uses an advanced `lastRotationAt`; rebound and `verified_not_restored` preserve the exact existing field/value pair. Every selected and previous freshness timestamp is no later than `finalizedAt`. Missing or mismatched disposition-to-freshness semantics, including a reissued existing lineage using `lastProvisionedAt` or a rebound changing its timestamp, fails closed. Rebound also records the distinct rebind operation identity, retained material-lineage identity, and exact new target binding. This mapping is target-state owner/projector behavior; the current static validator does not yet implement the projection or handoff check.
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
