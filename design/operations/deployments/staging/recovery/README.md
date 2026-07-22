# Staging Recovery Sanitization Evidence

Store one record per staging restore that originates from production data:

- `<recovery-ref>.json`

Required fields:

- `environment` (`staging`)
- `recoveryRef`
- `sourceBackup`
- `sanitizedAt`
- `sanitizedBy`
- `controlsApplied` (list of redaction/anonymization controls)
- `validationEvidence` (checks proving sanitized state before reopening traffic)
- `certificateReissuanceEvidence`
- `jwtRestoreHardeningEvidence`
- `databaseCredentialRotationEvidence`
- `backupConfidentialityEvidence`
- `externalCredentialValidation` with records for:
  - `backup-storage`
  - `asset-storage`
  - `outbound-comms`
  - `operator-credentials`

`SANITIZATION_EVIDENCE_REF` and external-credential evidence are separate inputs. `SANITIZATION_EVIDENCE_REF` must resolve to a staging recovery record whose `validationEvidence` proves the sanitization result; it must not resolve to an `externalCredentialValidation` child record or one of that record's evidence references. External credential validation remains a separate control group in the same recovery record.

Restore validation must fail closed unless `SANITIZATION_EVIDENCE_REF` is present, points under this staging recovery namespace, and contains non-empty `validationEvidence`. Passing external credential validation alone is not sufficient to release quarantine or reopen traffic.

`backupConfidentialityEvidence` must prove environment-scoped encryption, least-privilege access and audit, retention/secure deletion, and quarantine, sanitization, validation, and deletion of production-origin data before a non-production drill can expose workloads or retain evidence.
