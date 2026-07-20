# FireMUD Post-Restore Hardening

This document defines the mandatory hardening and validation steps that must complete after a restore and before a player-facing environment may reopen to traffic.

## Restore Quarantine

Restore isolation is mandatory. Before restoring manifests or PostgreSQL into a player-facing environment, operators must place the environment in quarantine so snapshot-era trust material cannot serve real traffic during recovery.

Required quarantine actions:

- scale Gateway and TCP Proxy workloads to zero, or detach their external `LoadBalancer` / Ingress exposure
- prevent DNS or traffic-manager cutover to the restored environment
- disable or scale down normal background processors, automation workers, schedulers, notification senders, webhooks, asset publishing workers, and any other workload that can create external or player-visible side effects
- keep Game Session tick executors and command intake stopped or under an enforced restore-safe startup gate until the coordination recovery mode is proven
- keep quarantine in place until post-restore hardening, external credential validation, required sanitization evidence, and smoke checks all succeed

It is not sufficient to rely on “operators will not send traffic yet” as a procedural control. The restored environment must be technically unable to accept player-facing traffic until the hardening sequence is complete.

Restore quarantine is a full restore-safe mode, not only an ingress block. Restored workloads must not be able to process queued work, emit outbound communications, publish assets, run gameplay automation, or create new Coordination Redis state with snapshot-era credentials before the hardening and coordination gates complete. Maintenance Jobs required for recovery may run, but they must use narrowly scoped service accounts and write evidence into the recovery record.

## Post-Restore Secret Hardening

Post-restore hardening is performed by a dedicated idempotent Kubernetes Job or equivalent playbook such as `post-restore-secret-hardening`. It classifies the recovery event before acting and records one disposition for every applicable credential class: `rotated`, `reissued`, `rebound`, or `verified_not_restored`.

Every PostgreSQL rewind still requires quarantine, old-authority fencing, empty Coordination Redis, session invalidation, epoch/fence advancement, durable and external reconciliation, binding validation, smoke, and controlled reopen. Credential treatment is event-specific:

- A same-boundary PostgreSQL-only rewind may use `verified_not_restored` when automation proves the credential authority remained current outside the restored artifact and is still correctly bound.
- A fresh cluster or namespace, restored Secret material, or an unprovable trust lineage requires fresh provisioning, rebinding, rotation, or reissuance as appropriate.
- Known or suspected compromise uses the complete compromise-response hard cutover for every affected authority.

No credential may be retained silently; its disposition and evidence are part of the canonical recovery record.

The hardening automation should use least-privilege service accounts:

- JWT rotation automation may read/update only the JWT signing-key Secret, the JWKS Secret or ConfigMap appropriate for the environment, and optionally the Account Service Deployment when restart is required for convergence.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

### 1. JWT signing key and JWKS rotation

- run compromise-style JWT cutover when JWT/JWKS material was restored, the trust boundary changed, or current external authority cannot be proved
- for a same-boundary PostgreSQL-only rewind, permit `verified_not_restored` only when automation proves the Account signing authority and validator JWKS remained outside the restored artifact and still match the expected binding
- remove restored keys from active trust material rather than retaining overlap from snapshot-era keysets
- keep JWT issuance and JWT-protected admission/control-plane traffic quarantined during cutover
- publish a fresh Account signing generation and `jwks.json`, then advance the environment issuer auth generation and complete required session invalidation
- refresh or restart every declared validator and prove that each rejects every restored `kid` and accepts the replacement `kid`
- verify Account Service health, immutable cutover evidence, and validator convergence before traffic reopen

### 2. Database credential rotation

- rotate or rebind database credentials when the credential, its owning Secret, or the database authority was restored or cannot be proved current; otherwise record `verified_not_restored`
- when rotation is required, run `db-credential-rotation` using a least-privilege service account and a crash-recoverable staged cutover
- read current app DB credentials from `postgres-credentials`
- use admin credentials to execute `ALTER ROLE ... PASSWORD ...`
- update the application Secret with the new password
- trigger rollout restarts of Deployments and StatefulSets that consume the Secret
- fail fast on any error

### 3. Certificate reissuance

- reissue workload, bridge, and operator leaf certificates when their Secrets or issuing boundary were restored, replaced, or cannot be proved current
- allow `verified_not_restored` for a same-boundary PostgreSQL-only rewind only after proving the current certificate resources and issuer binding remained outside the restored artifact
- default restore hardening rotates leaf certificates only; CA/root rotation is a separate compromise-response workflow
- require peer/validator convergence evidence before traffic reopen

### 4. External credential validation

Restoring a namespace also restores third-party credentials stored as Secrets. Post-restore hardening must either rotate/re-issue these credentials or re-bind the restored cluster to the correct per-environment secrets before any external traffic is allowed.

Canonical external validation matrix, limited to integrations enabled for the target environment:

- `backup-storage`
- `asset-storage`
- `outbound-comms`
- `operator-credentials`

Each class must produce:

- one machine-checkable validation result
- one explicit environment-isolation assertion
- one immutable evidence reference in the recovery record

The recovery record must also include:

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

Post-restore hardening records the event-classified disposition for each Tier A credential. Before quarantine can be lifted, automation updates the recovery evidence and, where provisioning or rotation changed the trust lineage, refreshes `design/operations/secret-compliance/<environment>.yaml` and its content-addressed supporting evidence. A `verified_not_restored` disposition references the still-current compliance evidence and does not invent a new freshness timestamp.

The refresh must include the credential classes affected by restore hardening:

- `jwt-signing-keys-jwks`
- `postgres-application-credentials`
- `backup-object-store-credentials`
- `asset-store-credentials` when external asset storage is enabled
- `operator-credentials`

Each refreshed credential record must use exactly one freshness timestamp: `lastProvisionedAt` when the fresh boundary was newly bootstrapped and has not completed its first planned rotation, or `lastRotationAt` when the restore-hardening job rotated an existing credential lineage. The referenced evidence payload must include an immutable artifact identifier and must be linked from the canonical recovery record under `secretComplianceRefresh`.

## Post-Restore Coordination Recovery Gate

After PostgreSQL is restored, but before normal application startup and before quarantine is lifted, the player-facing restore workflow must prove the environment-wide `cold_start_restore` recovery mode:

### `cold_start_restore`

- verify the entire Coordination Redis keyspace for the restored environment boundary is empty before rebuild; Cache Redis is a separate non-authoritative role and is not evidence for this check
- invalidate all gameplay and Account sessions from the restored timeline
- advance or recreate every gameplay-region epoch and fence before normal work can resume
- rebuild coordination state only from restored durable authority after every declared and enabled recovery participant and external-effect workflow has recorded a safe disposition such as converged, terminalized, invalidated, or durably fenced/disabled with its backlog retained
- record the backup artifact, restore/recovery tool digests, recovery-contract fingerprint, participant inventory, convergence results, and controlled-reopen evidence

### `scoped_reset_restore`

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. Quarantined experiments may explore its region inventory, pause/fence/reset, ledger convergence, and session policy, but they cannot lift quarantine or satisfy player-facing recovery readiness until a separate accepted design and proof package authorizes the mode.

Recovery automation must fail closed if it cannot prove the complete `cold_start_restore` contract. A successful PostgreSQL restore or empty Redis check alone is insufficient.

## Reopen Sequence

Runbooks should treat `post-restore-secret-hardening` as a mandatory step in any player-facing disaster recovery:

1. Enter restore-safe quarantine by disabling external traffic paths to Gateway and TCP Proxy and by stopping or restore-safe-fencing background processors, outbound integrations, automation workers, and Game Session tick executors.
2. Restore PostgreSQL and Kubernetes manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
3. Prove empty Coordination Redis, environment-wide gameplay and Account session invalidation, and every gameplay-region epoch/fence reset before any normal Game Session or automation startup can create fresh coordination state.
4. Run the complete enabled offline durable-participant and external-effect reconciliation inventory; unknown, missing, or unsafe outcomes keep quarantine closed, while a participant with a proved durable fenced/disabled disposition may retain backlog for later operator action.
5. Run `post-restore-secret-hardening` in the target namespace and require one proved event-classified disposition for every applicable credential.
6. When certificates were reissued, confirm workload, bridge, and operator peers converged; otherwise prove the current certificate authority was not restored and remains correctly bound.
7. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values.
8. Refresh the environment secret-compliance record and immutable evidence payload, and link that refresh from the recovery record.
9. For staging restores from production-origin data, ensure sanitization evidence exists and is referenced.
10. Start normal workloads in a controlled order and confirm application health checks, fresh login/session flows, gameplay smoke, JWT validation, and recovery-participant invariants while ingress remains quarantined.
11. Complete every pre-release control group, record operator approval, and advance the canonical recovery record to `ready_to_reopen`.
12. Use the idempotent crash-recoverable gated transition to remove quarantine through durable monotonic steps; uncertainty remains closed, and retries converge before external or player traffic is considered reopened.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run equivalent one-shot restore-hardening automation that performs the same control groups and writes the canonical recovery record before reopening player traffic.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
