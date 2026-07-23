# Staging Recovery Sanitization Evidence

Store one record per staging restore that originates from production data:

- `<recovery-ref>.json`

Every player-facing staging recovery record must follow the [canonical recovery record](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), including immutable `artifactErasureHighWater`, immutable `restoreHighWater` capture, and gap-free erasure replay before reopen. A restore sourced from production also adds the sanitization requirements below.

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

`SANITIZATION_EVIDENCE_REF` and external-credential evidence are separate inputs. `SANITIZATION_EVIDENCE_REF` must resolve to a staging recovery record whose `validationEvidence` proves the sanitization result; it must not resolve to an `externalCredentialValidation` child record or one of that record's evidence references. External credential validation remains a separate control group in the same recovery record.

Restore validation must fail closed unless `SANITIZATION_EVIDENCE_REF` is present, points under this staging recovery namespace, and contains non-empty `validationEvidence`. Passing external credential validation alone is not sufficient to release quarantine or reopen traffic.

`dev-tools/restores/validate-external-credentials.sh staging` validates the canonical hardening control-group names and the separate `SANITIZATION_EVIDENCE_REF` path. A pass from this helper is only one recovery control group and is not complete recovery proof.

`backupConfidentialityEvidence` must prove environment-scoped encryption, least-privilege access and audit, retention/secure deletion, and quarantine, sanitization, validation, and deletion of production-origin data before a non-production drill can expose workloads or retain evidence.

Sanitization evidence supplements the environment-wide cold-start, quarantine, convergence, hardening, smoke, erasure-replay, and controlled-reopen controller state; it does not replace those controls. Recovery continuation uses `continueRecovery(operationId, expectedPhase, evidenceRef)`; pause/lock remains internal controller state. The checked-in staging record is an immutable projection exported after the controller reaches `finalized`, not runtime authority.
