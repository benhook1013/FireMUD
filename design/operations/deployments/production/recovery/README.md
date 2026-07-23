# Production Recovery Evidence

Store one record per production restore as:

- `<recovery-ref>.json`

Every record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`, including environment-wide artifact lineage, immutable `artifactErasureHighWater`, immutable `restoreHighWater` capture, gap-free erasure replay, lifecycle status, cold-start proof, empty Coordination Redis, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

Production-specific requirements:

- `environment` (`production`)
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

`dev-tools/restores/validate-external-credentials.sh production` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to one of these records and validates the canonical `certificateReissuance`, `jwtHardening`, and `databaseCredentialRotation` control groups.

`backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, and retention/secure deletion. Whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion controls are mandatory.

Current implementation note: existing restore helpers do not produce the durable recovery-controller state machine, immutable erasure high-water replay, or its `collecting` -> `ready_to_reopen` -> `releasing` -> `finalized` reconciliation, so they cannot authorize player-facing reopen. Promotion preflight validates the canonical record's structural diagnostics but deliberately fails baseline reuse until participant, validator, and external-effect inventories plus linked immutable evidence are dereferenced. The checked-in record is a post-finalization immutable projection, not runtime authority. Recovery continuation is `continueRecovery(operationId, expectedPhase, evidenceRef)`; pause/lock is internal controller state.
