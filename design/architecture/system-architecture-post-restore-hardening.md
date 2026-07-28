# FireMUD Post-Restore Hardening

This document defines the mandatory hardening and validation steps that must complete after a restore and before a player-facing environment may reopen to traffic.

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
- `sa-jwt-rotation` may observe and reconcile the one Account-owned restore-cutover request, observe Account's phase/generation/publication status, run validator-convergence probes, and write the pre-created `jwt-rotation-status` evidence resource. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment. The materialization controller alone writes the private signing Secret; Account Service remains the only actor that advances issuer authority, invalidates restored Account authority and `game-session-account-delegation` lineages, publishes public JWKS, and commits the requested transition. Game Session separately invalidates gameplay bindings and gameplay session state from the durable cutoff event.
- Both workload identities and the Account request correlation must appear in immutable audit/evidence records keyed to the stable `restoreCutoverOperationId` and request digest. The recovery controller records the parent orchestration and the JWT evidence; `jwt-rotation-status` records JWT automation evidence, not a competing rotation state machine. Any missing RBAC/audit/evidence boundary keeps quarantine closed.

The JWT restore-hardening actor and RBAC boundary is the existing `jwt-rotation` Job/CronJob and `sa-jwt-rotation` service-account contract described in [Security Architecture](./system-architecture-security.md#jwt-key--jwks-rotation-workflow):

- `sa-jwt-rotation` may observe and reconcile the already-persisted Account-owned restore-cutover request and status, run validator-convergence probes, and write the pre-created `jwt-rotation-status` evidence resource. It may not create or request a transition. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment.
- Account Service remains the actor that owns restore cutover, issuer-authority advancement, Account-authority/delegation-lineage invalidation, and public JWKS publication. Game Session owns gameplay-binding/session invalidation and returns separate convergence evidence. The materialization controller alone has name-scoped `get`, `update`, and `patch` authority over the pre-created signing Secret and returns authenticated CAS/pruning evidence for Account reconciliation; Account has no Kubernetes API authority over that Secret, consumes it only through its read-only projected mount, and has update/patch authority over the public JWKS resource. Neither may list, create, or delete those pre-created resources.
- Validator refresh or restart uses the validator's inventory-declared refresh/deployment mechanism under operator or owning-controller authority; the JWT rotation Job only observes the resulting convergence and must not request that mutation or patch the Account Service Deployment. Validators receive public JWKS only.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

JWT post-restore rotation uses the fixed signing Secret and public JWKS resources while preserving Account Service authority: the materialization controller alone generates and resourceVersion-CAS-writes the private signing Secret; Account alone uses that private material for issuance, advances issuer authority, invalidates restored Account authority and `game-session-account-delegation` lineages, and publishes JWKS. The authenticated recovery-controller client invokes Account's idempotent `StartRestoreCutover` control-plane operation with the persisted `restoreCutoverOperationId`, immutable request digest, target boundary, requested mode, and expected authority context. Account durably creates or reuses that operation, starts the private-materialization request, and exposes `GetRestoreCutoverStatus` for reconciliation. The canonical operation advances through `PENDING`, `IN_PROGRESS`, and terminal `COMMITTED` or `FAILED`; retries with the same identity and digest resume or replay the existing result, conflicting digests fail closed, and retryable intermediate failure remains `IN_PROGRESS` with bounded next-attempt evidence rather than creating another cutover. `COMMITTED` records the replacement `kid`, issuer-authority generation, JWKS publication generation, Account authority/delegation invalidation high-water, and durable outbox cutoff consumed separately by Game Session for gameplay-binding/session invalidation. Before any recovery gate observes or reconciles the cutover, the recovery controller must durably persist that one restore-cutover request. If the request cannot be durably established or its result is ambiguous, quarantine remains closed. `sa-jwt-rotation` and the recovery orchestrator may observe status and reconcile validator and Game Session convergence, but neither may execute or resolve Account's state machine. This is a logical Account-owned transition, not a claim of cross-store physical atomicity: JWKS publication, durable Account authority state, Game Session invalidation, Redis projections, and validator convergence have separate effects that must be observed and reconciled before reopen. Jobs and validators do not read, export, or persist private keys. Recovery evidence contains only key identifiers, public validation material, and convergence proof.

### 1. JWT signing key and JWKS rotation

- run restore-hardening JWT rotation with compromise-style key cutover semantics
- remove restored keys from active trust material rather than retaining overlap from snapshot-era keysets
- keep JWT issuance and JWT-protected admission/control-plane traffic quarantined during cutover
- observe and reconcile Account's completed authority/delegation invalidation and Game Session's separate gameplay-binding/session invalidation from the single Account-owned restore-cutover cutoff under the recovery controller's stable `restoreCutoverOperationId`; lost-response reconciliation reuses that identity, and `cold_start_restore` must not issue a second cutover or become an invalidation writer
- refresh or restart every validator in the authoritative, complete validator inventory and prove that each rejects every restored `kid` and accepts the replacement `kid`; missing, unknown, unreachable, or non-converged validators fail closed
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

After PostgreSQL is restored, but before normal application startup and before quarantine is lifted, the player-facing restore workflow must prove the environment-wide `cold_start_restore` recovery mode:

### `cold_start_restore`

- verify the entire Coordination Redis keyspace for the restored environment boundary is empty before rebuild; Cache Redis is a separate non-authoritative role and is not evidence for this check
- rotate Coordination Redis credentials or rebind the restored workloads to fresh credentials and endpoints owned by the target environment; prove snapshot-era credentials are rejected and record immutable binding evidence before rebuilding any coordination state
- after the durable restore-cutover request exists, observe and reconcile the same Account-owned `restoreCutoverOperationId` requested by the JWT hardening control group; do not issue a second logical cutover
- reconcile the committed result of that same Account-owned restore-cutover operation, including proof that Account invalidated restored Account authority and delegation lineages and that Game Session separately invalidated restored gameplay bindings/session state, before normal work or player traffic can resume; this phase only observes/reconciles the results and never performs either invalidation
- advance or recreate every gameplay-region epoch and fence before normal work can resume
- after the Account cutover, rebuild and verify the issuer, account, tenant, and membership generation projections plus the issued-token projections from Account durable authority. Record per-scope generation and exact-token evidence and complete this rebuild before restore-safe smoke or any recovery continuation can pass; missing, stale, malformed, or mismatched projection state keeps quarantine closed
- where the restore affects the shared replay domain, complete replay-domain quarantine, `replayAdmissionFence` advancement, the configured lifetime-plus-skew hold, and durable replay-consume acknowledgement before the controller may reach `AWAITING_RESUME` or public resume may authorize release
- rebuild coordination state only from restored durable authority after authoritative, complete, reachable participant and external-effect inventories have recorded a safe disposition for every declared and enabled entry, such as converged, terminalized, invalidated, or durably fenced/disabled with its backlog retained. Missing, unknown, unreachable, or unsafe entries keep quarantine closed.
- record the backup artifact, snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, ordered evidence for both fixed replay intervals, and `erasureOverlayReconciliation` bound to `(artifactErasureHighWater, restoreHighWater]`. Its `sequenceDispositions[]` must persist exactly one canonical owner disposition for every sequence in the final interval; aggregate counts or a replay-through high-water are insufficient. Also record restore/recovery tool digests, recovery-contract fingerprint, participant inventory, convergence results, confidentiality proof, and controlled-reopen evidence in the durable recovery controller; emit checked-in projections only after finalization

### Deferred `scoped_reset_restore` (quarantined only)

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. Quarantined experiments may explore its region inventory, pause/fence/reset, ledger convergence, and session policy, but they cannot lift quarantine or satisfy player-facing recovery readiness until a separate accepted design and proof package authorizes the mode.

Recovery automation must fail closed if it cannot prove the complete `cold_start_restore` contract and advance the durable controller to `ready_to_reopen`. A successful PostgreSQL restore or empty Redis check alone is insufficient, and a deferred scoped-reset experiment remains quarantined.

## Reopen Sequence

Runbooks should treat `post-restore-secret-hardening` as a mandatory step in any player-facing disaster recovery:

1. Enter restore-safe quarantine by disabling external traffic paths to Gateway and TCP Proxy and by stopping or restore-safe-fencing background processors, outbound integrations, automation workers, and Game Session tick executors.
2. Restore PostgreSQL and Kubernetes manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
3. Establish the durable recovery-controller restore-cutover request before any later gate observes or reconciles it. Persist one stable `restoreCutoverOperationId` and immutable request digest; if persistence fails or the result is ambiguous, keep quarantine closed.
4. Prove empty Coordination Redis, rotate or rebind its credentials and endpoint to the target environment, prove snapshot-era credentials are rejected, and reset every gameplay-region epoch/fence before any normal Game Session or automation startup can create fresh coordination state.
5. Resolve authoritative, complete, reachable validator, durable-participant, and external-effect inventories and run the complete enabled reconciliation set; unknown, missing, unreachable, or unsafe outcomes keep quarantine closed, while a participant with a proved durable fenced/disabled disposition may retain backlog for later operator action.
6. Read the immutable artifact `artifactErasureHighWater` and capture immutable `initialCatchupHighWater` once. First verify and idempotently replay exactly every independently retained journal sequence in the initial interval `(artifactErasureHighWater, initialCatchupHighWater]`, recording its ordered, contiguous, complete, and gap-free evidence. Only after that interval is complete, perform one bounded final cutover that fences or serializes new erasure assignment, captures immutable `restoreHighWater` once, and replays exactly the final interval `(initialCatchupHighWater, restoreHighWater]`. Persist `erasureOverlayReconciliation.sequenceDispositions[]` with exactly one canonical owner disposition for every sequence in that final interval, not only aggregate counts. Prove both intervals are contiguous, complete, gap-free, and free of duplicate application before installing `restoreHighWater` as the restored consumer cursor. Never advance either boundary from a moving current high-water. A missing sequence, integrity failure, unavailable owner, ambiguous boundary, or incomplete convergence keeps quarantine closed.
7. Run `post-restore-secret-hardening` in the target namespace and wait for success. The authenticated recovery-controller client submits the already-persisted request to Account's `StartRestoreCutover` operation. The separate delegated `jwt-rotation` Job only observes and reconciles the same Account operation while Account publishes the new signing generation and invalidates restored Account authority/delegation lineages; Game Session separately invalidates restored gameplay bindings/session state from the committed cutoff. Neither recovery workload performs those owner-specific mutations itself.
8. Confirm workload, bridge, and operator leaf certificates have been reissued and peers converged.
9. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values.
10. Refresh the environment secret-compliance record and immutable evidence payload, and link that refresh from the durable recovery-controller state.
11. For staging restores from production-origin data, ensure sanitization and confidentiality evidence exists and is referenced.
12. Run only an explicitly fenced restore-safe smoke profile. It may check health, JWT validation, restored data, and recovery-participant invariants, but it cannot process queued work, create fresh Coordination Redis state, issue normal player sessions, run gameplay automation, publish assets, or produce outbound effects.
13. Complete every pre-release control group and record its immutable evidence in the durable controller. Verify that the controller has advanced to `ready_to_reopen` only after all gates, including the Account generation/issued-token projection rebuild, restore-safe smoke, ordered gap-free erasure replay, replay-domain proof where applicable, and final-interval per-sequence owner dispositions, pass.
14. Call `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=ready_to_reopen`. It validates the exact durable operation, phase, maintenance-lock token, and immutable evidence binding, then idempotently reconciles the controller into `AWAITING_RESUME`; it does not release quarantine or expose traffic.
15. Call the public `resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)` safety gate with canonical `expectedPhase=AWAITING_RESUME`. The controller must validate the exact durable operation, expected phase, recovery scope, maintenance-lock token, replay/Account evidence, and immutable evidence binding; `evidenceRef` must resolve to complete, immutable pre-release evidence whose operation, scope, required gate results, and evidence digests match the durable controller state. Any missing, stale, partial, ambiguous, or mismatched binding/evidence fails closed without mutation. A successful call durably records `RESUME_AUTHORIZED` while retaining the maintenance lock and traffic fence; it does not release them or expose traffic.
16. Only after `RESUME_AUTHORIZED` is durably recorded may the internal release phase proceed for that same operation. It idempotently reconciles `releasing -> finalized` and moves already-started restore-safe workloads toward normal operation in controlled order. During `releasing`, external ingress remains closed and every workload remains technically fenced from accepting or creating normal side effects until its release step is applied and observed. The controller reaches `finalized` only after the complete release is observed, and only then may player traffic be exposed. A timeout, failed readiness check, or ambiguous apply keeps traffic closed and quarantine active, records the failed release attempt, and schedules idempotent release reconciliation against the same durable operation; no rollback promise or compensating release is assumed. Retries must not regress release state or reapply a completed release. Export the immutable checked-in recovery and traffic-open evidence projections only after every release step is durably and observably complete and the controller is `finalized`.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run equivalent one-shot restore-hardening automation that performs the same control groups, calls `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with `expectedPhase=ready_to_reopen`, calls public `resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)` with `expectedPhase=AWAITING_RESUME`, and only then allows the internal release phase to reach `finalized`. Its immutable checked-in recovery projection is exported only after the controller reaches `finalized`.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
