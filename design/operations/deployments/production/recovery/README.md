# Production Recovery Evidence

Store one record per production restore as:

- `<recovery-ref>.json`

Every record must follow the [canonical recovery record](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), including environment-wide artifact lineage, snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, gap-free erasure replay, lifecycle status, cold-start proof, empty Coordination Redis with target-environment credential rebinding, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

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

`dev-tools/restores/validate-external-credentials.sh production` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to the complete recovery evidence document containing these records, not to one nested record. The JSON must include top-level `environment`, `recoveryRef`, canonical `certificateReissuance`, `jwtHardening`, and `databaseCredentialRotation` control groups, plus `externalCredentialValidation.records`.

`backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, and retention/secure deletion. Whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion controls are mandatory.

Current implementation note: `validate-external-credentials.sh` independently validates the canonical credential-hardening groups. The promotion path still stops fail closed before `PREFLIGHT-BACKUP-001` can accept nested dispositions or freshness as promotion authority; those deeper checks remain target-state validation. The executable cannot yet read durable recovery-controller authority, validate complete inventory membership and linked immutable evidence, or reconcile `collecting` -> `ready_to_reopen` -> `AWAITING_RESUME` -> `RESUME_AUTHORIZED` -> `releasing` -> `finalized`; `validate_recovery_baseline` therefore returns fail closed and production traffic-open preflight is unconditionally unavailable. The checked-in record is a post-finalization immutable projection, not runtime authority. Public recovery uses `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` followed by `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`; pause/lock and success release are internal controller state.

## Controlled Reopen Sequence

The durable recovery operation does not reopen production traffic directly from continuation. After all recovery evidence and pre-release gates pass:

1. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses `expectedPhase=ready_to_reopen` and transitions the operation to `AWAITING_RESUME` without releasing its fence or maintenance lock.
2. The authenticated public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses `expectedPhase=AWAITING_RESUME` and records `RESUME_AUTHORIZED` for the exact operation and its recorded scope; it does not release the lock or reopen traffic.
3. Only the internal success-release phase applies and verifies the reopen postconditions, then transitions the operation to `finalized`. A failed or abandoned operation remains fenced and uses the exact-scope audited `coordination-maintenance release-lock --operation-id <operationId> --scope <scope> --maintenance-lock-token <token> --reason <reason> --evidence-ref <evidenceRef>` control.
