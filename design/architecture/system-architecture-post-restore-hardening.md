# FireMUD Post-Restore Hardening

This document defines the mandatory hardening and validation steps that must complete after a restore and before a player-facing environment may reopen to traffic.

## Restore Quarantine

Restore isolation is mandatory. Before restoring manifests or PostgreSQL into a player-facing environment, operators must place the environment in quarantine so snapshot-era trust material cannot serve real traffic during recovery.

Required quarantine actions:

- scale Gateway and TCP Proxy workloads to zero, or detach their external `LoadBalancer` / Ingress exposure
- prevent DNS or traffic-manager cutover to the restored environment
- keep quarantine in place until post-restore hardening, external credential validation, required sanitization evidence, and smoke checks all succeed

It is not sufficient to rely on “operators will not send traffic yet” as a procedural control. The restored environment must be technically unable to accept player-facing traffic until the hardening sequence is complete.

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

## Post-Restore Coordination Recovery Gate

After PostgreSQL is restored, but before quarantine is lifted, the restore workflow must prove that Coordination Redis is operating in exactly one approved recovery mode:

### `cold_start_restore`

- verify the Coordination Redis keyspace is empty for coordination prefixes
- complete the same reopen evidence quality required for scoped reset recovery
- record evidence that reset-sensitive session/auth state was dropped or re-established

### `scoped_reset_restore`

- run the authoritative reset handshake for the affected scope
- record evidence for pause completion, epoch bump, scoped reset execution, ledger reconcile / command convergence, metadata reinitialization, and post-reset smoke success
- record whether reset-sensitive session/auth prefixes were invalidated

Recovery automation must fail closed if the restore event cannot be classified into one of these two modes with evidence.

## Reopen Sequence

Runbooks should treat `post-restore-secret-hardening` as a mandatory step in any player-facing disaster recovery:

1. Enter restore quarantine by removing or disabling external traffic paths to Gateway and TCP Proxy.
2. Restore PostgreSQL and Kubernetes manifests.
3. Run `post-restore-secret-hardening` in the target namespace and wait for success.
4. Confirm workload, bridge, and operator leaf certificates have been reissued and peers converged.
5. Run `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>` with environment-specific expected values.
6. For staging restores from production-origin data, ensure sanitization evidence exists and is referenced.
7. Confirm application health checks, login/session flows, and JWT validation.
8. Only then remove quarantine and route external or player traffic to the restored cluster.

For hobby/self-hosted environments that do not use the Kubernetes Job template directly, operators must run equivalent one-shot restore-hardening automation that performs the same four control groups and writes the canonical recovery record before reopening player traffic.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, revert to the previous known-good password and Secret value and repeat later with more diagnostics.
