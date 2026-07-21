# Hobby Recovery Evidence

Store one record per hobby/self-hosted restore as:

- `<recovery-ref>.json`

Every record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`, including environment-wide artifact lineage, immutable `artifactErasureHighWater`, immutable `restoreHighWater` capture, gap-free erasure replay, lifecycle status, cold-start proof, empty Coordination Redis, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

## Implementation Status

- `dev-tools/restores/validate-external-credentials.sh hobby-self-hosted` currently still expects the legacy `certificateReissuanceEvidence`, `jwtRestoreHardeningEvidence`, and `databaseCredentialRotationEvidence` aliases instead of the canonical control-group names; a legacy-script pass is not complete recovery proof.
- The checked-in hobby evidence and writer do not yet export or validate the durable controller's complete cold-start recovery state, including `collecting` -> `ready_to_reopen` -> `releasing` -> `finalized`; player-facing reopen remains blocked. The checked-in record is a post-finalization immutable projection, not runtime authority.
- Recovery continuation is public only as `continueRecovery(operationId, expectedPhase, evidenceRef)`; pause/lock remains an internal durable phase, not a public recovery verb.

Hobby-specific requirements:

- `environment` (`hobby-self-hosted`)
- `recoveryRef`
- `externalCredentialValidation`
- `certificateReissuance`
- `jwtHardening`
- `databaseCredentialRotation`
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
