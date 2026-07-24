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

Post-restore hardening is performed by a dedicated Kubernetes Job such as `post-restore-secret-hardening` that coordinates multiple flows:

The hardening automation should use least-privilege service accounts:

- JWT rotation automation may only request the Account-owned restore-cutover operation, orchestrate validator probes, and observe the Account-published JWKS and cutover status/evidence. It must not publish or update `jwt-jwks`, read or update `jwt-signing-keys`, advance issuer authority, invalidate sessions, or patch the Account Service Deployment.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

JWT post-restore rotation preserves Account Service custody of the non-exportable private signer and makes Account the sole JWKS publication authority. Account owns one idempotent logical restore-cutover transition that publishes a fresh JWKS, advances the issuer authority generation, and invalidates old Account and gameplay session authority through its durable security event. The hardening Job may request and observe that transition and validator convergence, but may not perform any of those cutover steps itself. This is a logical Account-owned transition, not a claim of cross-store physical atomicity: JWKS publication, durable authority state, Redis session projections, and validator convergence have separate effects that must be observed and reconciled before reopen. Jobs and validators do not read, export, or persist private keys. Recovery evidence contains only key identifiers, public validation material, and convergence proof.

### 1. JWT signing key and JWKS rotation

- run restore-hardening JWT rotation with compromise-style key cutover semantics
- remove restored keys from active trust material rather than retaining overlap from snapshot-era keysets
- keep JWT issuance and JWT-protected admission/control-plane traffic quarantined during cutover
- request Account to execute the single restore-cutover operation that publishes a fresh signing generation and `jwks.json`, advances the environment issuer authority generation, and invalidates old session authority; the Job must not perform these as separate cutover steps
- refresh or restart every validator in the authoritative, complete validator inventory and prove that each rejects every restored `kid` and accepts the replacement `kid`; missing, unknown, unreachable, or non-converged validators fail closed
- verify Account Service health, immutable restore-cutover evidence, and validator convergence before traffic reopen

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
- request and observe the Account-owned restore-cutover transition that invalidates all gameplay and Account sessions from the restored timeline
- advance or recreate every gameplay-region epoch and fence before normal work can resume
- rebuild coordination state only from restored durable authority after authoritative, complete, reachable participant and external-effect inventories have recorded a safe disposition for every declared and enabled entry, such as converged, terminalized, invalidated, or durably fenced/disabled with its backlog retained. Missing, unknown, unreachable, or unsafe entries keep quarantine closed.
- record the backup artifact, snapshot-bound `artifactErasureHighWater`, immutable `initialCatchupHighWater`, immutable final-cutover `restoreHighWater`, gap-free erasure replay result, restore/recovery tool digests, recovery-contract fingerprint, participant inventory, convergence results, confidentiality proof, and controlled-reopen evidence in the durable recovery controller; emit checked-in projections only after finalization

### Deferred `scoped_reset_restore` (quarantined only)

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. Quarantined experiments may explore its region inventory, pause/fence/reset, ledger convergence, and session policy, but they cannot lift quarantine or satisfy player-facing recovery readiness until a separate accepted design and proof package authorizes the mode.

Recovery automation must fail closed if it cannot prove the complete `cold_start_restore` contract and advance the durable controller to `ready_to_reopen`. A successful PostgreSQL restore or empty Redis check alone is insufficient, and a deferred scoped-reset experiment remains quarantined.

## Reopen Sequence

Runbooks should treat `post-restore-secret-hardening` as a mandatory step in any player-facing disaster recovery:

1. Enter restore-safe quarantine by disabling external traffic paths to Gateway and TCP Proxy and by stopping or restore-safe-fencing background processors, outbound integrations, automation workers, and Game Session tick executors.
2. Restore PostgreSQL and Kubernetes manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
3. Prove empty Coordination Redis, rotate or rebind its credentials and endpoint to the target environment, prove snapshot-era credentials are rejected, and reset every gameplay-region epoch/fence before any normal Game Session or automation startup can create fresh coordination state.
4. Resolve authoritative, complete, reachable validator, durable-participant, and external-effect inventories and run the complete enabled reconciliation set; unknown, missing, unreachable, or unsafe outcomes keep quarantine closed, while a participant with a proved durable fenced/disabled disposition may retain backlog for later operator action.
5. Run `post-restore-secret-hardening` in the target namespace and wait for success. As part of its Account-owned JWT control group, the Job requests and observes the restore-cutover transition that publishes the new signing generation, invalidates restored Account and gameplay session authority, and proves validator convergence; it does not perform those Account-owned mutations itself.
6. Confirm workload, bridge, and operator leaf certificates have been reissued and peers converged.
7. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values.
8. Refresh the environment secret-compliance record and immutable evidence payload, and link that refresh from the durable recovery-controller state.
9. For staging restores from production-origin data, ensure sanitization and confidentiality evidence exists and is referenced.
10. Run only an explicitly fenced restore-safe smoke profile. It may check health, JWT validation, restored data, and recovery-participant invariants, but it cannot process queued work, create fresh Coordination Redis state, issue normal player sessions, run gameplay automation, publish assets, or produce outbound effects.
11. Complete every pre-release control group and record its immutable evidence in the durable controller. Verify that the controller has advanced to `ready_to_reopen` only after all gates, including restore-safe smoke and gap-free erasure replay, pass.
12. Call `continueRecovery(operationId, expectedPhase, evidenceRef)` for `expectedPhase=ready_to_reopen`. Its atomic transition reconciles the internal `ready_to_reopen -> releasing -> finalized` phases and moves already-started restore-safe workloads toward normal operation in controlled order. During `releasing`, external ingress remains closed and every workload remains technically fenced from accepting or creating normal side effects until its release step is applied and observed. The controller reaches `finalized` only after the complete release is observed, and only then may player traffic be exposed. A timeout, failed readiness check, or ambiguous apply keeps traffic closed, records the failed release attempt, and retries or rolls back the partially released workload to its restore-safe fence through the same idempotent controller operation. Export the immutable checked-in recovery and traffic-open evidence projections only after `finalized`.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run equivalent one-shot restore-hardening automation that performs the same control groups and calls `continueRecovery(operationId, expectedPhase, evidenceRef)` on the durable recovery controller. Its immutable checked-in recovery projection is exported only after the controller reaches `finalized`.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
