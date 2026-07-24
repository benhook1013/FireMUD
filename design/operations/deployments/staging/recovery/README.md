# Staging Recovery Sanitization Evidence

Store one record per staging restore that originates from production data:

- `<recovery-ref>.json`

Before release, store the immutable sanitization result separately as `<recovery-ref>.sanitization.json`. This pre-release artifact uses `schemaVersion=recovery-sanitization-evidence/v1` and includes `environment=staging`, `recoveryRef`, `operationId`, `deploymentEventId`, `backupArtifactDigest`, `sanitizedAt`, `sanitizedBy`, `controlsApplied`, and `validationEvidence`. Its operation, event, and artifact identifiers must equal the durable recovery controller and restored artifact for the current restore; evidence from another restore cannot be reused.

Every player-facing staging recovery record must follow the [canonical recovery record](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), including snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, and gap-free erasure replay before reopen. A restore sourced from production also adds the sanitization requirements below.

Staging production-origin requirements:

- `environment` (`staging`)
- `recoveryRef`
- `sourceBackup`
- `sanitizedAt`
- `sanitizedBy`
- `controlsApplied` (list of redaction/anonymization controls)
- `validationEvidence` (checks proving sanitized state before reopening traffic)
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

Sanitization evidence supplements the environment-wide cold-start, quarantine, convergence, hardening, smoke, erasure-replay, and controlled-reopen controller state; it does not replace those controls. Recovery continuation uses `continueRecovery(operationId, expectedPhase, evidenceRef)`; pause/lock remains internal controller state. After `finalized`, the exporter writes `<recovery-ref>.json` as the immutable recovery projection and sets its `sanitizationEvidenceRef` to the exact pre-release artifact. The finalized projection is not runtime authority.
