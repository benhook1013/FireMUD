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
- `externalCredentialValidation` with records for:
  - `backup-storage`
  - `asset-storage`
  - `outbound-comms`
  - `operator-credentials`

`dev-tools/restores/validate-external-credentials.sh staging` requires `SANITIZATION_EVIDENCE_REF` to point to one of these records.
