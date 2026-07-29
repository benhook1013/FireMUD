# Hobby Recovery Evidence

Store one record per hobby/self-hosted restore as:

- `<recovery-ref>.json`

Every record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`, including environment-wide artifact lineage, snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, gap-free erasure replay, lifecycle status, cold-start proof, empty Coordination Redis with target-environment credential rebinding, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

## Implementation Status

- `dev-tools/restores/validate-external-credentials.sh hobby-self-hosted` validates the canonical `certificateReissuance`, `jwtHardening`, and `databaseCredentialRotation` control groups; passing this credential check is not complete recovery proof.
- The checked-in hobby evidence and writer do not yet export or validate the durable controller's complete cold-start recovery state, including `collecting` -> `ready_to_reopen` -> `AWAITING_RESUME` -> `RESUME_AUTHORIZED` -> `releasing` -> `finalized`; player-facing reopen remains blocked. The checked-in record is a post-finalization immutable projection, not runtime authority.

The plaintext `maintenanceLockToken` must be supplied to recovery tooling only through protected stdin, a file descriptor, or a permissioned `0600` token file. It must never be passed as a command-line argument or appear in shell history, process listings, logs, URLs, or evidence.

The complete controller sequence below, including public `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` followed by `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`, is target-state-only and unavailable until the durable controller is implemented and proved. Current operators must not invoke either control; they must use the fail-closed [Current Operator Fallback](../../../../architecture/system-architecture-redis-reset-and-recovery.md#current-operator-fallback) and the current deployment recovery procedures. In the target state, pause/lock and success release remain internal durable phases, not public recovery verbs.

## Controlled Reopen Sequence

The durable recovery operation does not reopen player traffic directly from continuation. After all recovery and pre-release gates pass:

1. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses `expectedPhase=ready_to_reopen` and transitions the operation to `AWAITING_RESUME` without releasing its fence or maintenance lock.
2. The authenticated public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses the lowercase wire-form `expectedPhase=awaiting_resume`, which the server compares with the internal durable phase `AWAITING_RESUME`, and records `RESUME_AUTHORIZED` for the exact operation and its recorded scope; it does not release the lock or reopen traffic.
3. Only the internal success-release phase applies and verifies the reopen postconditions, then transitions the operation to `finalized`. A failed or abandoned operation remains fenced. The `coordination-maintenance release-lock ...` control is target-state-only and unavailable until implemented and proven; current operators must not run it. Use the shipped [Redis recovery procedures](../../../../architecture/system-architecture-redis-operations.md) and [Redis incident escalation runbook](../../../../architecture/system-architecture-redis-incident-runbook.md) instead. The future release-lock contract remains exact-scope, audited, token-protected, evidence-bound, and idempotent.

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
