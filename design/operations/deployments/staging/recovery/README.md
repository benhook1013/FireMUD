# Staging Recovery Sanitization Evidence

Store one record per staging restore that originates from production data:

- `<recovery-ref>.json`

Before release, store the immutable sanitization result separately as `<recovery-ref>.sanitization.json`. This pre-release artifact uses `schemaVersion=recovery-sanitization-evidence/v1` and includes `environment=staging`, `recoveryRef`, `operationId`, `deploymentEventId`, `backupArtifactDigest`, `sanitizedAt`, `sanitizedBy`, `controlsApplied`, and `validationEvidence`. Its operation, event, and artifact identifiers must equal the durable recovery controller and restored artifact for the current restore; evidence from another restore cannot be reused.

Every player-facing staging recovery record must follow the [canonical recovery record](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), including snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, and gap-free erasure replay before reopen. A restore sourced from production also adds the sanitization requirements below.

## Implementation Status

- `validate-external-credentials.sh` validates only the canonical hardening control groups and the separate sanitization-evidence path; a pass is not complete recovery proof.
- The checked-in exporter writes `<recovery-ref>.json` only after `finalized`; the resulting immutable projection is not runtime authority, and the complete durable controller validation and player-facing staging reopen proof are not yet available.
- The `coordination-maintenance recover`, `continue-recovery`, `resume`, and `release-lock` surface is target-state-only and unavailable until the durable controller, Account projection repair/replacement, replay/evidence path, and end-to-end proof are implemented. Current operators must not invoke any of those commands; in particular, `release-lock` is prohibited until it is implemented and proven and is not a current unlock or reopen substitute.

## Current Operator Fallback

For a current staging Redis outage, cold start, or incomplete recovery:

- Keep protected admission, gameplay mutation, command intake, and affected coordination writers fenced or stopped.
- Use only the shipped `PauseTicksForScope` pause and `GetRuntimeOwnershipStatus` status surface for the supported `{tenantId, gameInstanceId}` boundary, plus read-only `coord_ops_ro` Redis inspection. Follow the current failover/AOF procedures and escalation path in the [Redis incident runbook](../../../../architecture/system-architecture-redis-incident-runbook.md) and [Redis Operations](../../../../architecture/system-architecture-redis-operations.md); do not use target-state reset or reopen commands.
- If the durable recovery controller, Account projection repair/replacement, replay quarantine/fence, or immutable evidence path is unavailable, stale, or ambiguous, abort any destructive wipe, recovery continuation, `resume`, or reopen attempt. Preserve the AOF and incident evidence, leave the fence in place, and escalate. There is no supported current full-wipe or unlock substitute.

Staging production-origin requirements:

- `environment` (`staging`)
- `recoveryRef`
- `sourceBackup`
- `sanitizedAt`
- `sanitizedBy`
- `controlsApplied` (list of redaction/anonymization controls)
- `validationEvidence` (checks proving sanitized state before reopening traffic)
- `sanitizationEvidenceRef` (finalized projection reference to the exact pre-release sanitization artifact)
- `certificateReissuance`
- `jwtHardening`
- `databaseCredentialRotation`
- `backupConfidentialityEvidence`
- `externalCredentialValidation` with records for:
  - `backup-storage`
  - `asset-storage`
  - `outbound-comms`
  - `operator-credentials`

`SANITIZATION_EVIDENCE_REF` and external-credential evidence are separate inputs. `SANITIZATION_EVIDENCE_REF` must resolve to the current restore's `<recovery-ref>.sanitization.json` pre-release artifact; it must not resolve to the post-finalization recovery projection, an `externalCredentialValidation` child record, or one of that record's evidence references. External credential validation remains a separate control group.

Restore validation must fail closed unless `SANITIZATION_EVIDENCE_REF` is present, points under this staging recovery namespace, contains non-empty validation fields, and its `recoveryRef`, `operationId`, `deploymentEventId`, and `backupArtifactDigest` match the controller state submitted for release. Passing external credential validation alone is not sufficient to release quarantine or reopen traffic.

`dev-tools/restores/validate-external-credentials.sh staging` validates the canonical hardening control-group names and the separate `SANITIZATION_EVIDENCE_REF` path. A pass from this helper is only one recovery control group and is not complete recovery proof.

`backupConfidentialityEvidence` must prove environment-scoped encryption, least-privilege access and audit, retention/secure deletion, and quarantine, sanitization, validation, and deletion of production-origin data before a non-production drill can expose workloads or retain evidence.

Sanitization evidence supplements the environment-wide cold-start, quarantine, convergence, hardening, smoke, erasure-replay, and controlled-reopen controller state; it does not replace those controls. In the target state, public recovery uses `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` followed by `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`; pause/lock and success release remain internal controller state. Current operators must not invoke those controls or `release-lock` until the complete controller and proof path is implemented and proven. After `finalized`, the exporter writes `<recovery-ref>.json` as the immutable recovery projection and sets its `sanitizationEvidenceRef` to the exact pre-release artifact.

In the target-state workflow, the plaintext `maintenanceLockToken` must be supplied to recovery tooling only through protected stdin, a file descriptor, or a permissioned `0600` token file. It must never be passed as a command-line argument or appear in shell history, process listings, logs, URLs, or evidence.

## Controlled Reopen Sequence (Target State Only; Unavailable Today)

The following is a target-state sequence, not a current staging operator instruction. The durable recovery operation does not reopen staging traffic directly from continuation. Current operators must not invoke `continueRecovery`, `resume`, or `release-lock`; keep staging fenced and use the current fallback above until the complete controller and proof path is implemented and proven. After sanitization and all other pre-release gates pass in that future workflow:

1. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses `expectedPhase=ready_to_reopen` and transitions the operation to `AWAITING_RESUME` without releasing its fence or maintenance lock.
2. The authenticated public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses the lowercase wire-form `expectedPhase=awaiting_resume`, which the server compares with the internal durable phase `AWAITING_RESUME`, and records `RESUME_AUTHORIZED` for the exact operation and its recorded scope; it does not release the lock or reopen traffic.
3. Only the internal success-release phase applies and verifies the reopen postconditions, then transitions the operation to `finalized`. In the future target-state workflow, a failed or abandoned operation remains fenced and uses the exact-scope audited `coordination-maintenance release-lock --operation-id <operationId> --scope <scope> --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>` control. That control is unavailable today; current operators must leave the fence in place and follow the fallback above.
