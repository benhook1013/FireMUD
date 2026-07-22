# Hobby Recovery Evidence

Store one record per hobby/self-hosted restore as:

- `<recovery-ref>.json`

Required fields:

- `environment` (`hobby-self-hosted`)
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

`dev-tools/restores/validate-external-credentials.sh hobby-self-hosted` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to one of these records.

`backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and any required quarantine or sanitization controls for non-production recovery data.
