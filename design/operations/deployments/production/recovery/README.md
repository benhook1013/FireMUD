# Production Recovery Evidence

Store one record per production restore as:

- `<recovery-ref>.json`

Required fields:

- `environment` (`production`)
- `recoveryRef`
- `externalCredentialValidation`
- `certificateReissuanceEvidence`
- `jwtRestoreHardeningEvidence`
- `databaseCredentialRotationEvidence`
- `backupConfidentialityEvidence`

`externalCredentialValidation.records` must include `backup-storage`, `asset-storage`, `outbound-comms`, and `operator-credentials`. Each record must include:

- `status` (`pass`)
- `evidenceRef`
- `isolationAssertion`
- `validationMethod`
- `validatedAt`
- `validatedBy`
- `observedValue`

`dev-tools/restores/validate-external-credentials.sh production` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to one of these records.

`backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and any production-origin non-production drill quarantine, sanitization, validation, and deletion controls.
