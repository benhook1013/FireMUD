# Production Recovery Evidence

Store one record per production restore as:

- `<recovery-ref>.json`

Every record must follow the [canonical recovery record](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), including environment-wide artifact lineage, snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, gap-free erasure replay, lifecycle status, cold-start proof, empty Coordination Redis with target-environment credential rebinding, session and epoch/fence invalidation, recovery-participant dispositions, hardening, smoke, and controlled-reopen fields.

## Implementation Status

- `validate-external-credentials.sh` independently validates the canonical credential-hardening groups; that check is not complete recovery proof.
- The promotion path still stops fail closed before `PREFLIGHT-BACKUP-001` can accept nested dispositions or freshness as promotion authority; those deeper checks remain target-state validation.
- The executable cannot yet read durable recovery-controller authority, validate complete inventory membership and linked immutable evidence, or reconcile `collecting` -> `ready_to_reopen` -> `AWAITING_RESUME` -> `RESUME_AUTHORIZED` -> `releasing` -> `phase=finalized` with `status=SUCCEEDED`; `validate_recovery_baseline` therefore returns fail closed and production traffic-open preflight is unconditionally unavailable.
- The checked-in record is a post-success-finalization immutable projection, not runtime authority.
- The `coordination-maintenance recover`, `continue-recovery`, `resume`, and `release-lock` surface is target-state-only and unavailable until the durable controller, Account projection repair/replacement, replay/evidence path, and end-to-end proof are implemented. Current operators must not invoke any of those commands; in particular, `release-lock` is prohibited until it is implemented and proven and is not a current unlock or reopen substitute.

## Current Operator Fallback

For a current production Redis outage, cold start, or incomplete recovery:

- Keep protected admission, gameplay mutation, command intake, and affected coordination writers fenced or stopped.
- The per-scope `SetAutomationAdmissionMode` and `GetAutomationDrainStatus` surfaces are not a current recovery authorization: the live Automation owner does not yet provide a durable request-result acknowledgement or matching readback identity. Do not treat a successful RPC response, admission mode/epoch, fresh `observedAt`, `activeExecutionCount=0`, or `pendingCancelableWorkItemCount=0` as proof of recovery containment. Keep the fence in place and escalate when per-scope state is missing or ambiguous. See the [Scripting & Automation control-plane API owner contract](../../../../architecture/system-architecture-scripting-control-plane-api.md#setautomationadmissionmode) for the target Set/Get identity and fingerprint semantics. For current recovery, use deployment-wide Automation containment only with explicit impact approval, complete affected-scope enumeration from the durable PostgreSQL/runtime inventory, and a distinct durable deployment/owner acknowledgement plus authoritative readback that no executor claims or scheduled `ScriptOutboxQueueRebuildJob` invocations continue. That containment must include a scheduler-disable control or authoritative evidence that scheduled rebuild execution cannot proceed; a per-scope admission pause alone does not stop the job.
- Use only the shipped `PauseTicksForScope` pause and `GetRuntimeOwnershipStatus` status surface for the supported `{tenantId, gameInstanceId}` boundary. `PauseTicksForScope` is currently an instance-wide administrative pause/resume fallback: `regionId` is rejected, and this control is not the exact regional or ADR-0048-complete operator action. For any remote/shared Coordination Redis inspection, apply the [owner transport and credential gate](../../../../architecture/system-architecture-redis-ops-access.md#ops-user-vs-application-user), including authenticated TLS peer-certificate and endpoint-hostname/SAN verification; a missing or ambiguous gate leaves the fence in place and requires escalation. Follow the current failover/AOF procedures and escalation path in the [Redis incident runbook](../../../../architecture/system-architecture-redis-incident-runbook.md) and [Redis Operations](../../../../architecture/system-architecture-redis-operations.md); do not use target-state reset or reopen commands.
- If the durable recovery controller, Account projection repair/replacement, replay quarantine/fence, or immutable evidence path is unavailable, stale, or ambiguous, abort any destructive wipe, recovery continuation, `resume`, or reopen attempt. Preserve the AOF and incident evidence, leave the fence in place, and escalate. There is no supported current full-wipe or unlock substitute.

Production-specific requirements:

- `environment` (`production`)
- `recoveryRef`
- `externalCredentialValidation`
- `certificateReissuance`
- `jwtHardening`
- `databaseCredentialRotation`
- `backupConfidentialityEvidence`

The canonical recovery record's `credentialApplicability` must cover the closed nine-class credential universe. The five internal classes (`jwt-signing-keys-jwks`, `postgres-application-credentials`, `workload-leaf`, `bridge-leaf`, and `operator-leaf`) are always `applicable` and therefore require a valid disposition plus their corresponding hardening evidence. Production `backup-storage` and `operator-credentials` are also always `applicable` because the production environment requires both the backup binding and operator control-plane binding. `operator-leaf` is the internal operator certificate class; `operator-credentials` is its one canonical external environment-binding/compliance mapping, and both remain distinct recovery keys with their own evidence. Only `asset-storage` and `outbound-comms` may be `not_applicable` in this production profile. The shared validator selects this required-applicable set by environment; non-production profiles may mark absent `backup-storage` or `operator-credentials` bindings `not_applicable` under their own requirements. `credentialDispositions` contains exactly the applicable classes, so a non-applicable class has no disposition.

`externalCredentialValidation.records` must include exactly `backup-storage`, `asset-storage`, `outbound-comms`, and `operator-credentials`, with the record shape matching `credentialApplicability`. The production `operator-credentials` record is the canonical mapped operator-binding record and must be `applicable` with `status=pass`; it must never use the `not_applicable` shape.

- an `applicable` record has exactly `status=pass`, `evidenceRef`, `isolationAssertion`, `validationMethod`, `validatedAt`, `validatedBy`, and non-secret `observedValue`;
- a `not_applicable` record has exactly `status=not_applicable`, `reason=credential-class-not-present`, and a non-empty immutable `evidenceRef`; it must not contain validation fields or observed credential detail.

The record status and disposition must agree with the applicability classification; missing, unknown, extra, or contradictory classes fail closed.

`dev-tools/restores/validate-external-credentials.sh production` requires `EXTERNAL_CREDENTIAL_EVIDENCE_REF` to point to the complete recovery evidence document containing these records, not to one nested record. The JSON must include top-level `environment`, `recoveryRef`, canonical `certificateReissuance`, `jwtHardening`, and `databaseCredentialRotation` control groups, plus `externalCredentialValidation.records`.

`backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, and retention/secure deletion. Whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion controls are mandatory.

In the target-state workflow, the plaintext `maintenanceLockToken` must be supplied to recovery tooling only through protected stdin, a file descriptor, or a permissioned `0600` token file. It must never be passed as a command-line argument or appear in shell history, process listings, logs, URLs, or evidence.

## Controlled Reopen Sequence (Target State Only; Unavailable Today)

The following is a target-state sequence, not a current production operator instruction. The durable recovery operation does not reopen production traffic directly from continuation. Current operators must not invoke `continueRecovery`, `resume`, or `release-lock`; keep production fenced and use the current fallback above until the complete controller and proof path is implemented and proven. After all recovery evidence and pre-release gates pass in that future workflow:

1. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses `expectedPhase=ready_to_reopen` and transitions the operation to `AWAITING_RESUME` without releasing its fence or maintenance lock.
2. The authenticated public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` uses the lowercase wire-form `expectedPhase=awaiting_resume`, which the server compares with the internal durable phase `AWAITING_RESUME`, and records `RESUME_AUTHORIZED` for the exact operation and its recorded scope; it does not release the lock or reopen traffic.
3. Only the internal success-release phase applies and verifies the reopen postconditions, then transitions the operation to `phase=finalized` with `status=SUCCEEDED`. In the future target-state workflow, an explicitly abandoned pre-release failure remains fenced and may use the exact-scope audited `coordination-maintenance release-lock --operation-id <operationId> --scope <scope> --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>` control only before `RESUME_AUTHORIZED`. After release authorization, including before or during `partial_release_reconciling`, abandonment and `release-lock` are prohibited; the operation retains its fence and reconciles through the same internal release worker. The control is unavailable today; current operators must leave the fence in place and follow the fallback above.
