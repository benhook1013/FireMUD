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

Restore quarantine is a full restore-safe mode, not only an ingress block. Restored workloads must not be able to process queued work, emit outbound communications, publish assets, run gameplay automation, or create new Coordination Redis state with snapshot-era credentials before the hardening and coordination gates complete. Maintenance Jobs required for recovery may run, but they must use narrowly scoped service accounts and write evidence into the durable recovery-controller state.

## Post-Restore Secret Hardening

Post-restore hardening is performed by a dedicated Kubernetes Job such as `post-restore-secret-hardening` that coordinates multiple flows:

The hardening automation should use least-privilege service accounts:

- JWT rotation automation may read/update only the JWT signing-key Secret, the JWKS Secret or ConfigMap appropriate for the environment, and optionally the Account Service Deployment when restart is required for convergence.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

### 1. JWT signing key and JWKS rotation

- run restore-hardening JWT rotation with compromise-style key cutover semantics
- remove restored keys from active trust material rather than retaining overlap from snapshot-era keysets
- wait for fresh signing keys and regenerated `jwks.json`
- verify Account Service health and validator convergence before traffic reopen

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

For staging restores sourced from production-origin snapshots, `SANITIZATION_EVIDENCE_REF` must point at the required in-repo sanitization evidence before traffic may reopen.

### 5. Secret-compliance evidence refresh

Post-restore hardening changes the Tier A trust lineage for the environment. Before quarantine can be lifted, operators must refresh `design/operations/secret-compliance/<environment>.yaml` and its immutable supporting evidence so promotion, DR-readiness, and traffic-open checks no longer point at pre-restore credential evidence.

The refresh must include the credential classes affected by restore hardening:

- `jwt-signing-keys-jwks`
- `postgres-application-credentials`
- `backup-object-store-credentials`
- `asset-store-credentials` when external asset storage is enabled
- `operator-credentials`

Each refreshed credential record must use exactly one freshness timestamp: `lastProvisionedAt` when the fresh boundary was newly bootstrapped and has not completed its first planned rotation, or `lastRotationAt` when the restore-hardening job rotated an existing credential lineage. The referenced evidence payload must include an immutable artifact identifier and must be linked from the durable recovery-controller state under `secretComplianceRefresh`; the checked-in projection mirrors this after finalization.

## Post-Restore Coordination Recovery Gate

After PostgreSQL is restored, but before normal application startup and before quarantine is lifted, the restore workflow must prove the approved environment-wide `cold_start_restore` mode and record its evidence in the durable recovery controller. A checked-in recovery JSON projection is emitted only after the controller finalizes the release.

### `cold_start_restore`

- verify the Coordination Redis keyspace is empty for coordination prefixes
- record that all surviving Coordination Redis state was replaced or cleared rather than merged with restored PostgreSQL
- record environment-wide session invalidation, epoch/fence reset, participant convergence, confidentiality proof, and smoke evidence in the recovery controller

### Deferred `scoped_reset_restore` (quarantined only)

- `scoped_reset_restore` with surviving Coordination Redis is not an approved player-facing recovery mode and must not release quarantine
- isolated experiments may record pause, epoch, reset, reconciliation, and smoke evidence, but that evidence is not recovery readiness or traffic-open proof
- support requires a separate decision and proof package for complete scope inventory, stale-state rejection, session policy, and end-to-end reconciliation

Recovery automation must fail closed unless the restore event proves environment-wide `cold_start_restore` and the durable controller reaches `ready_to_reopen`. A deferred scoped-reset experiment remains quarantined.

## Reopen Sequence

Runbooks should treat `post-restore-secret-hardening` as a mandatory step in any player-facing disaster recovery:

1. Enter restore-safe quarantine by disabling external traffic paths to Gateway and TCP Proxy and by stopping or restore-safe-fencing background processors, outbound integrations, automation workers, and Game Session tick executors.
2. Restore PostgreSQL and Kubernetes manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
3. Record and prove environment-wide `cold_start_restore` in the durable recovery controller before normal Game Session or automation startup can create fresh coordination state. Do not require a checked-in recovery JSON projection at this point.
4. Run `post-restore-secret-hardening` in the target namespace and wait for success.
5. Confirm workload, bridge, and operator leaf certificates have been reissued and peers converged.
6. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values.
7. Refresh the environment secret-compliance record and immutable evidence payload, and link that refresh from the durable recovery-controller state.
8. For staging restores from production-origin data, ensure sanitization evidence exists and is referenced.
9. Start normal workloads in a controlled order and confirm application health checks, login/session flows, gameplay smoke, and JWT validation while ingress remains quarantined; record the completed evidence and operator approval in the durable controller's `ready_to_reopen` state.
10. Request the controller's gated release. Only after it observes the release and reaches `finalized` may the workflow export the immutable checked-in recovery/traffic-open JSON projection and route external or player traffic to the restored cluster.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run equivalent one-shot restore-hardening automation that performs the same control groups and records the durable controller's `ready_to_reopen` state before release. The checked-in recovery record is exported only after the controller reaches `finalized`.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
