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

Current implementation note: `validate-external-credentials.sh` validates the canonical credential-hardening groups, and promotion preflight validates the checked-in recovery projection's structural fields, nested dispositions, and freshness. The executable still cannot read durable recovery-controller authority, validate complete inventory membership and linked immutable evidence, or reconcile `collecting` -> `ready_to_reopen` -> `releasing` -> `finalized`; `validate_recovery_baseline` therefore returns fail closed and production traffic-open preflight is unconditionally unavailable. The checked-in record is a post-finalization immutable projection, not runtime authority. Recovery continuation is `continueRecovery(operationId, expectedPhase, evidenceRef)`; pause/lock is internal controller state.
