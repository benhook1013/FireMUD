# Hobby Recovery Evidence

Store one record per hobby/self-hosted restore as:

- `<recovery-ref>.json`

Every record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`, including environment-wide artifact lineage, lifecycle status, cold-start proof, empty Coordination Redis, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

Hobby-specific requirements:

- `environment` (`hobby-self-hosted`)
- `recoveryRef`
- `externalCredentialValidation`
- `certificateReissuance`
- `jwtHardening`
- `databaseCredentialRotation`

`externalCredentialValidation.records` must include `backup-storage`, `asset-storage`, `outbound-comms`, and `operator-credentials`. Each record must include:

- `status` (`pass`)
- `evidenceRef`
- `isolationAssertion`
- `validationMethod`
- `validatedAt`
- `validatedBy`
- `observedValue`

`dev-tools/restores/validate-external-credentials.sh hobby-self-hosted` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to one of these records, but it still expects the legacy `certificateReissuanceEvidence`, `jwtRestoreHardeningEvidence`, and `databaseCredentialRotationEvidence` aliases. It must be updated to the canonical control-group names; a legacy-script pass is not complete recovery proof.

Current implementation note: the checked-in hobby evidence and writer do not yet produce or validate the complete cold-start recovery record, so player-facing reopen remains blocked.
