# Production Recovery Evidence

Store one record per production restore as:

- `<recovery-ref>.json`

Every record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`, including environment-wide artifact lineage, lifecycle status, cold-start proof, empty Coordination Redis, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

Production-specific requirements:

- `environment` (`production`)
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

`dev-tools/restores/validate-external-credentials.sh production` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to one of these records, but it still expects the legacy `certificateReissuanceEvidence`, `jwtRestoreHardeningEvidence`, and `databaseCredentialRotationEvidence` aliases. It must be updated to the canonical control-group names; a legacy-script pass is not complete recovery proof.

Current implementation note: existing restore helpers do not produce the durable recovery-controller state machine or its `collecting` -> `ready_to_reopen` -> `releasing` -> `finalized` reconciliation, so they cannot authorize player-facing reopen. The checked-in record is a post-finalization immutable projection, not runtime authority.
