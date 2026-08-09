# FireMUD Post-Restore Hardening

This document defines the mandatory hardening and validation steps that must complete after a restore and before a player-facing environment may reopen to traffic.

## Implementation Status

This document is target-state architecture, not evidence that the full restore workflow is live.

- **Live behavior:** the repository has restore-safe guidance, preflight validation, and evidence contracts, but does not yet provide a proven end-to-end player-facing restore-hardening controller or automation path.
- **Target-only behavior:** the durable recovery controller, delegated hardening Jobs, `continueRecovery(... expectedPhase=ready_to_reopen ...)`, public `resume(... expectedPhase=awaiting_resume ...)`, and the internal guarded release through `finalized` remain required target-state behavior. They must not be inferred from the documentation or from partial tooling.

## Restore Quarantine

Restore isolation is mandatory. Before restoring manifests or PostgreSQL into a player-facing environment, operators must place the environment in quarantine so snapshot-era trust material cannot serve real traffic during recovery.

Required quarantine actions:

- scale Gateway and TCP Proxy workloads to zero, or detach their external `LoadBalancer` / Ingress exposure
- prevent DNS or traffic-manager cutover to the restored environment
- disable or scale down normal background processors, automation workers, schedulers, notification senders, webhooks, asset publishing workers, and any other workload that can create external or player-visible side effects
- keep Game Session tick executors and command intake stopped or under an enforced restore-safe startup gate until the coordination recovery mode is proven
- keep quarantine in place until post-restore hardening, external credential validation, required sanitization evidence, smoke checks, and the durable recovery-controller `ready_to_reopen` gate all succeed

It is not sufficient to rely on “operators will not send traffic yet” as a procedural control. The restored environment must be technically unable to accept player-facing traffic until the hardening sequence is complete.

Restore quarantine is a full restore-safe mode, not only an ingress block. Restored workloads must not be able to process queued work, emit outbound communications, publish assets, run gameplay automation, or create new Coordination Redis state with snapshot-era credentials before the hardening and coordination gates complete. Maintenance Jobs required for recovery may run, but they must use narrowly scoped service accounts and submit their results to the durable recovery controller.

## Post-Restore Secret Hardening

Post-restore hardening is performed by a dedicated Kubernetes Job such as `post-restore-secret-hardening` that coordinates multiple flows. It is one recovery control group, not one all-powerful workload: JWT rotation is a delegated Account-owned sub-operation, while database credential rotation, certificate reissuance, external-credential rebinding, evidence collection, and the recovery-controller gate remain separate control flows.

The workload and authority relationship is explicit:

- `post-restore-secret-hardening` is the recovery orchestrator. Its separately provisioned service account may invoke the declared recovery Jobs, read their bounded evidence, update the durable recovery-controller workflow, and write only the evidence needed for that workflow. It must not read JWT private material, write `jwt-signing-keys` or `jwt-jwks`, patch Account deployments, or impersonate Account authority.
- `jwt-rotation` is a separate Job/CronJob workload using the dedicated `sa-jwt-rotation` service account. The same artifact and phased protocol may be invoked as the JWT control group of post-restore hardening, but it remains a delegated workload with its own narrow RBAC and audit identity; it is not an inline privilege of the parent Job and is not a second independent restore cutover.
- `sa-jwt-rotation` only observes the persisted Account-owned restore-cutover request/status, including Account's phase/generation/publication status, runs validator-convergence probes/readbacks, and records validator and Game Session convergence evidence; it does not reconcile or mutate the Account request/state machine, patch or restart validators, or mutate Game Session. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment. Account Service remains the only actor that advances issuer authority, invalidates restored Account authority and `game-session-account-delegation` lineages, publishes public JWKS, and commits the requested transition. Target non-exportable-signer mode has no application private-key Secret or mount; its recovery proof observes Account's authenticated signer operation and public-JWKS convergence instead. Game Session APIs separately invalidate gameplay bindings and gameplay session state from the durable cutoff event.
- Both workload identities and the Account request correlation must appear in immutable audit/evidence records keyed to the stable `restoreCutoverOperationId` and request digest. The recovery controller records the parent orchestration and the JWT evidence; `jwt-rotation-status` records JWT automation evidence, not a competing rotation state machine. Any missing RBAC/audit/evidence boundary keeps quarantine closed.

The JWT restore-hardening actor and RBAC boundary is the existing `jwt-rotation` Job/CronJob and `sa-jwt-rotation` service-account contract described in the [JWT signing-key rotation contract](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative):

- For each persisted Account-owned restore-cutover request identified by `restoreCutoverOperationId`, `sa-jwt-rotation` only observes the request/status, runs validator-convergence probes/readbacks, and records validator and Game Session convergence evidence under narrow read/probe RBAC. It does not reconcile or mutate the Account request/state machine, patch or restart validators, or mutate Game Session, and it may not create or request a transition. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment.
- Account Service remains the actor that owns restore cutover, issuer-authority advancement, Account-authority/delegation-lineage invalidation, and public JWKS publication. Game Session owns gameplay-binding/session invalidation and returns separate convergence evidence. In the interim mounted fallback, the materialization controller alone has name-scoped `get`, `update`, and `patch` authority over the pre-created signing Secret; under [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative), it may execute only an Account-authorized private-slot pruning operation during reconciliation and may not independently choose slots or mutate desired key-ring state. It returns the canonical authenticated CAS/pruning execution evidence for Account reconciliation; the JWT contract owns its exact request, authority, and evidence semantics. Account has no Kubernetes API authority over that Secret, consumes it only through its read-only projected mount, and has update/patch authority over the public JWKS resource. Neither may list, create, or delete those pre-created resources. In target non-exportable-signer mode, those Secret, mount, and materialization-controller permissions do not exist; recovery observes the Account-owned signer health/generation proof and public-JWKS convergence instead.
- Validator refresh or restart uses the validator's inventory-declared refresh/deployment mechanism under operator or owning-controller authority; `sa-jwt-rotation` only probes/readbacks the resulting convergence under narrow read/probe RBAC and records the evidence. It must not request that mutation or patch the Account Service Deployment. Validators receive public JWKS only.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

JWT post-restore rotation follows the Account-owned cutover and custody authority in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). In target non-exportable-signer mode, Account authenticates and delegates the replacement signing operation to the signer; no private-key Secret, application mount, or materialization-controller path exists. In the interim mounted fallback, the materialization controller alone generates and resourceVersion-CAS-writes the fixed private signing Secret, but it may execute private-slot pruning only when Account authorizes it during reconciliation; Account alone uses that private material for issuance, advances issuer authority, invalidates restored Account authority and `game-session-account-delegation` lineages, and publishes JWKS. The authenticated recovery-controller client invokes Account's idempotent `StartRestoreCutover` control-plane operation with the persisted `restoreCutoverOperationId`, immutable request digest, target boundary, requested mode, and expected authority context. Account first looks up the persisted operation by identity. For a new request, it validates that the configured custody backend is available and matches the requested mode before creating an operation or any signer/materialization side effect. For an existing operation, Account compares the request digest before requiring a live backend: a matching persisted operation may replay its terminal result or stable `IN_PROGRESS` status without live backend availability, while a conflicting digest fails closed. Exact retries do not create another cutover; any continuation remains bound to the same persisted operation and digest. Account exposes `GetRestoreCutoverStatus` for reconciliation. The canonical operation advances through `PENDING`, `IN_PROGRESS`, and terminal `COMMITTED` or `FAILED`; retries with the same identity and digest resume or replay the existing result, and retryable intermediate failure remains `IN_PROGRESS` with bounded next-attempt evidence rather than creating another cutover. `COMMITTED` records the replacement `kid`, issuer-authority generation, JWKS publication generation, Account authority/delegation invalidation high-water, and durable outbox cutoff consumed separately by Game Session for gameplay-binding/session invalidation. Before any recovery gate observes or reconciles the cutover, the recovery controller must durably persist that one restore-cutover request. If the request cannot be durably established or its result is ambiguous, quarantine remains closed. `sa-jwt-rotation` observes the persisted Account-owned restore-cutover request/status, probes/readbacks validator and Game Session convergence under narrow read/probe RBAC, and records the resulting evidence; it does not reconcile or mutate the Account request/state machine, patch or restart validators, or mutate Game Session. The validator deployment/refresh mechanism and Game Session APIs perform those effects. For the same `restoreCutoverOperationId`, the recovery orchestrator may observe status and record parent orchestration/evidence, but it must not reconcile validator or Game Session convergence or execute or resolve the Account state machine. This is a logical Account-owned transition, not a claim of cross-store physical atomicity: JWKS publication, durable Account authority state, Game Session invalidation, Redis projections, and validator convergence have separate effects that must be observed and reconciled before reopen. Jobs and validators do not read, export, or persist private keys. Cutover completeness evidence is generation-bound and non-secret: it records the stable operation/request digest, relevant scope and projection identifiers, authority/JWKS generations, bounded counts, and cryptographic hashes or digests sufficient to prove the expected projection contents without retaining token material. It must never contain bearer-token values, passwords, private keys, raw credentials, or credential-bearing connection strings. Issued-token projection verification remains required, but it is recorded through generation-bound identifiers, counts, hashes/digests, and controlled acceptance/rejection outcomes rather than bearer-token values.

Issued-token projection evidence uses one canonical `issued-token-projection/v1` digest per environment-wide restore-cutover operation and issuer-authority generation. The unhashed evidence object contains exactly `schemaVersion`, `restoreCutoverOperationId`, `environmentId`, `issuerAuthorityGeneration`, `scope`, `entryCount`, and `entries`; `scope` is exactly `environment-wide`. Each entry contains exactly the non-secret tuple `tokenHash`, `recordSchemaVersion`, `accountId`, `tokenProfile`, `audience`, `jti`, `iat`, `exp`, `tokenGeneration`, and `state`. `tokenHash` is SHA-256 over the exact ASCII bytes of the complete compact JWT serialization as issued (`base64url(header).base64url(payload).base64url(signature)`), without trimming, decoding, re-encoding, Unicode normalization, or claim reserialization, encoded as 64 lowercase hexadecimal characters without a prefix. The raw JWT may exist only transiently in the Account-owned issuance or validation path that computes this value; it is never serialized into projection evidence. Producers sort entries by `tokenHash`, reject duplicates, encode identifiers and enum strings without case folding, encode integers as JSON integers without leading zeroes, normalize strings to Unicode NFC, and serialize the exact object as UTF-8 RFC 8785 JSON Canonicalization Scheme bytes. The projection digest is SHA-256 over those bytes and is encoded as `sha256:` followed by 64 lowercase hexadecimal characters. Producers and validators use the same field set, normalization, entry order, and canonical bytes; a missing or extra field, duplicate or reordered entry, non-canonical encoding, count mismatch, unsupported schema, or digest mismatch fails closed before the evidence can satisfy smoke, continuation, or reopen. Raw JWTs and private material are never fields in the projection.

For a new `StartRestoreCutover` request, Account must resolve the configured environment custody backend and validate that the requested custody mode is available and matches that backend before creating an operation record, signer request, materialization request, or other cutover side effect. A missing, unavailable, or mismatched backend/mode fails closed. An exact retry first matches the persisted operation identity and digest; a matching terminal result or stable `IN_PROGRESS` operation is replayable without live backend availability, while a conflicting digest fails closed.

### 1. JWT signing key and JWKS rotation

- run restore-hardening JWT rotation with compromise-style key cutover semantics
- remove restored keys from active trust material rather than retaining overlap from snapshot-era keysets
- keep JWT issuance and JWT-protected admission/control-plane traffic quarantined during cutover
- observe and record Account's completed authority/delegation invalidation and Game Session's separate gameplay-binding/session invalidation from the single Account-owned restore-cutover cutoff under the recovery controller's stable `restoreCutoverOperationId`; Account and Game Session APIs perform those effects, lost-response observation reuses that identity, and `cold_start_restore` must not issue a second cutover or become an invalidation writer
- the owning validator deployment/refresh mechanism refreshes or restarts every validator in the authoritative, complete validator inventory, and `sa-jwt-rotation` probes/readbacks each validator to prove rejection of every restored `kid` and acceptance of the replacement `kid`; missing, unknown, unreachable, or non-converged validators fail closed
- verify Account Service health, immutable restore-cutover evidence, and validator convergence before traffic reopen

`validatorConvergenceEvidence` must contain one immutable record per inventoried validator. Each record identifies the validator workload and environment, its applicable JWT profiles/audiences, the observed refresh or restart, the JWKS generation and `kid` set observed, rejection of every restored `kid`, acceptance of the replacement `kid`, rejection of inapplicable audiences, the reserved-probe authorization/side-effect denial result, observation time, and an immutable evidence reference. A missing per-validator record, an unexpected acceptance or rejection, or a probe that authorizes an operation fails the recovery gate. The aggregate `jwtHardening` evidence also records the rotation Job reference, the stable `restoreCutoverOperationId`, the immutable restore-cutover request digest, resulting key IDs, issuer authority-generation advance, and prior-generation registry rejection proof; no record contains private key material.

### 2. Database credential rotation

- run `db-credential-rotation` using least-privilege service account
- read current app DB credentials from `postgres-credentials`
- use admin credentials to execute `ALTER ROLE ... PASSWORD ...`
- update the application Secret with the new password
- trigger rollout restarts of Deployments and StatefulSets that consume the Secret
- fail fast on any error

### 3. Certificate reissuance

- reissue workload mTLS certificates used by `FIREMUD_GRPC_*`
- reissue TCP Proxy to Gateway WebSocket mTLS credentials
- reissue operator client certificates used for internal control-plane access
- default restore hardening rotates leaf certificates only; CA/root rotation is a separate compromise-response workflow
- require peer/validator convergence evidence before traffic reopen

### 4. External credential validation

Restoring a namespace also restores third-party credentials stored as Secrets. Post-restore hardening must either rotate/re-issue these credentials or re-bind the restored cluster to the correct per-environment secrets before any external traffic is allowed.

Canonical external validation matrix:

- `backup-storage`
- `asset-storage`
- `outbound-comms`
- `operator-credentials`

Each class must produce:

- one machine-checkable validation result
- one explicit environment-isolation assertion
- one immutable evidence reference in the durable recovery-controller state

The durable recovery-controller state must also include:

- `validationMethod`
- `validatedAt`
- `validatedBy`
- `observedValue` or fingerprint sufficient to prove what was checked without disclosing the secret

`observedValue` must be redacted by design. Never store secret values, passwords, private keys, bearer tokens, or full connection strings containing credentials.

Minimum execution contract:

- backup-storage validation must confirm bucket and endpoint binding plus non-production isolation constraints
- asset-storage validation must confirm publishing target isolation
- outbound-comms validation must confirm SMTP or webhook targets and enforce “staging cannot send production messages”
- operator-credentials validation must confirm the expected operator certificate or credential binding

Before enabling external traffic, run:

- `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>`

Expected inputs include environment-specific values such as:

- `EXPECTED_PG_DUMP_BUCKET`
- `EXPECTED_ASSET_STORE_BUCKET`
- `EXPECTED_ASSET_STORE_ENDPOINT`
- `EXPECTED_SMTP_HOST`
- `EXTERNAL_CREDENTIAL_EVIDENCE_REF`
- optionally `PRODUCTION_PG_DUMP_BUCKET` and `PRODUCTION_ASSET_STORE_BUCKET` when validating staging isolation

For staging restores sourced from production-origin snapshots, `SANITIZATION_EVIDENCE_REF` must point at an immutable `<recovery-ref>.sanitization.json` pre-release artifact whose operation, deployment event, and backup-artifact digest match the durable controller. The finalized recovery projection references that same artifact after the controller reaches `finalized`; it is not used circularly as the pre-release input.

### 5. Secret-compliance evidence refresh

Post-restore hardening changes the Tier A trust lineage for the environment. Before release, operators must refresh the environment secret-compliance record and its immutable supporting evidence, submit that ref to the recovery controller, and ensure promotion, DR-readiness, and traffic-open checks no longer point at pre-restore credential evidence. Any checked-in recovery projection is exported after controller finalization.

The refresh must include the credential classes affected by restore hardening:

- `jwt-signing-keys-jwks`
- `postgres-application-credentials`
- `backup-object-store-credentials`
- `asset-store-credentials` when external asset storage is enabled
- `operator-credentials`

Each refreshed credential record must use exactly one freshness timestamp: `lastProvisionedAt` when the fresh boundary was newly bootstrapped and has not completed its first planned rotation, or `lastRotationAt` when the restore-hardening job rotated an existing credential lineage. The referenced evidence payload must include an immutable artifact identifier and must be linked from the durable recovery-controller state under `secretComplianceRefresh`; the checked-in projection mirrors this after finalization.

## Post-Restore Coordination Recovery Gate

The restore mode, reopen, and fixed erasure-replay boundary are canonical in [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#restore-cutover-evidence-boundary). Post-restore hardening retains the local gate that must be evidenced before that owner-defined boundary can pass:

- Coordination Redis must be empty for the restored environment boundary, with fresh target-environment credentials and rejection of snapshot-era credentials; Cache Redis is not evidence for this gate.
- The hardening workflow observes one Account-owned restore-cutover operation and records separate evidence for Account authority/delegation invalidation and Game Session gameplay-binding/session invalidation. For that `restoreCutoverOperationId`, `sa-jwt-rotation` only probes/readbacks validator and Game Session convergence under narrow read/probe RBAC and records evidence; the owning validator deployment/refresh mechanism and Game Session APIs perform those mutations, while the recovery orchestrator observes and records the resulting evidence.
- JWT, issuer/account/tenant/membership projection, validator-convergence, replay-domain, participant, external-effect, confidentiality, and smoke evidence must be complete and non-secret before quarantine can be lifted. Missing, stale, malformed, unknown, unreachable, or unsafe evidence keeps quarantine closed.
- Evidence retains the owner-defined artifact and erasure high-water references, tool digests, recovery-contract fingerprint, participant inventory, convergence results, and controlled-reopen references; checked-in projections are emitted only after owner-defined finalization.

### Deferred `scoped_reset_restore` (quarantined only)

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. Quarantined experiments cannot lift quarantine or satisfy player-facing recovery readiness until a separate accepted design and proof package authorizes the mode.

Recovery automation must fail closed when it cannot prove the owner-defined `cold_start_restore` contract. A successful PostgreSQL restore or empty Redis check alone is insufficient.

## Reopen Sequence

The restore controller's reopen sequence is canonical in [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record). Runbooks must still treat `post-restore-secret-hardening` as mandatory and retain these local hardening consequences:

1. Enter restore-safe quarantine and hold normal workloads, ingress, background processors, outbound integrations, automation, and Game Session tick executors behind the restore-safe gate.
2. Run the Account-owned JWT cutover observer, database credential rotation, certificate reissuance, external-credential validation, and secret-compliance refresh in the target environment. The JWT Job observes the Account operation; it does not perform Account or Game Session invalidation.
3. Require complete validator, participant, external-effect, confidentiality, sanitization, and restore-safe smoke evidence. Any missing, stale, partial, ambiguous, or unsafe result keeps quarantine closed.
4. Submit the hardening evidence to the durable recovery controller. This document's handoff ends when that evidence is accepted for the owner-defined `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` and public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` gates; hardening never invokes release directly. Permit traffic only after the owning lifecycle has finalized. Checked-in recovery and traffic-open projections are post-finalization evidence, not release authority.

Hobby/self-hosted operators must run equivalent one-shot hardening automation with the same control groups and fail-closed evidence requirements when the Kubernetes Job template is unavailable.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
