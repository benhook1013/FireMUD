# FireMUD Deployment Runbook

This runbook describes the **standard deployment flow** for FireMUD in Kubernetes-backed environments.

For high-level CI/CD architecture, see `design/architecture/system-architecture-cicd.md`. This document focuses on the concrete steps and checks an operator performs when rolling out a new version.

## Prerequisites

- CI pipeline has produced immutable image digests for each service to deploy.
- Database migrations have been validated (see `design/architecture/system-architecture-database-migrations.md`).
- Redis, PostgreSQL, and core infrastructure components (Gateway, TCP Proxy, Observability stack) are healthy.
- The operator has `kubectl` access and a kubeconfig for the target Kubernetes cluster (staging, production, or hobby-self-hosted) from a secure admin workstation or bastion host.

## Implementation Notes

This runbook describes the required deployment flow. Current automation is executable for the existing expected-binding report reference, mounted JWT/JWKS path contract, and partial/static-only hobby traffic-open evidence checks, plus the production fail-closed placeholder, but some required gates and evidence production remain operator- or future-automation-owned:

- Fresh-boundary restore bootstrap and post-restore secret-compliance refresh are canonical requirements, but current restore scripts do not yet automate the full evidence chain.
- Current backup/preflight automation does not prove environment-wide artifact lineage, enforced quarantine, empty-Redis cold start, complete recovery-participant convergence, or controlled reopen. Production first-live, post-rewind reopen, and `roll-forward-only` promotion remain blocked until that proof exists.
- Production release digest manifests are canonical release-lineage evidence, but current overlay CI does not yet enforce their presence or schema.
- Expected-binding validation is first-pass repository/render validation. Real first-live and reopen decisions require current environment evidence and the durable controller authority described below; checked-in projection files cannot authorize the release transaction.
- `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, and `PREFLIGHT-JWT-ROTATION-001` are not yet implemented and remain fail-closed, so the checked-in legacy Secret-backed JWT/JWKS deployment mode, shared-HMAC drift, and private-key distribution topology cannot satisfy player-facing JWT readiness.

Operators must treat missing real-environment evidence as a blocker even when static preflight policy IDs are present. A successful static report without the required traffic-open, restore, release-manifest, and secret-compliance evidence is not enough to open player-facing traffic.

## Environment Bootstrap (First Deployment Only)

Before the first player-facing deployment into `hobby-self-hosted`, `staging`, or `production`, operators must complete a bootstrap step that creates the minimum environment trust and secret set before any workload apply:

1. Provision the target namespace and registry pull credentials used by workloads.
2. Provision per-environment PostgreSQL credentials (`postgres-credentials`) and, when rotation Jobs are used, `postgres-admin-credentials`.
3. Select and record the custody state before applying workloads. The one actual checked-in deployment mode is legacy Secret-backed JWT/JWKS wiring (`jwt-signing-keys` and `jwt-jwks` Secrets); it may appear in pre-apply diagnostics but is non-authorizing and is not an accepted player-facing custody state. For `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, establish trusted pre-apply evidence for the pre-created interim `jwt-signing-keys` Secret, the separate interim Account-owned public `jwt-jwks` ConfigMap, and the materialization controller's name-scoped RBAC. Account has exclusive mounted/signing use of the private bundle; the materialization controller has only bounded, Account-authorized byte handling needed to materialize it and no competing signing authority. The [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative) define custody and operation authority; this runbook requires every private-slot `generate`, `materialize`, `resourceVersion`-CAS, and `prune` action to use a persisted Account-authorized operation, with the controller executing only that operation and unable to choose slots or mutate desired key-ring state independently. For target non-exportable signer mode, establish trusted pre-apply evidence for the approved signer reference, target Account-owned public `jwt-jwks` ConfigMap, Account publication/CAS authority, and no private-material mount or distribution. In both accepted modes, `FIREMUD_AUTH_JWKS_PATH` is mandatory for Account and every validator; only the interim mode sets `FIREMUD_AUTH_JWT_SECRET_PATH`, while target non-exportable signer mode leaves it unset. These bootstrap records prove intended bindings and custody boundaries only; post-apply live signer, public-JWKS, and validator convergence is a separate owner-produced gate.
4. Provision cert-manager issuer bindings and certificate resources required for workload gRPC mTLS, Gateway internal mTLS WebSocket listener, TCP Proxy bridge mTLS client identity, and operator-only client identities where applicable.
5. Provision per-environment external integration credentials: backup/object-store, asset-store, outbound-communications, and operator-control-plane credentials as needed for that environment class.
6. Run `./dev-tools/deploy/preflight.py <environment>` and require the common checks `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001`, plus exactly one authenticated custody proof whose `proofId`, `custodyMode`, and `contractVersion` exactly match the selected mode: `PREFLIGHT-JWT-INTERIM-001` for `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` or `PREFLIGHT-JWT-002` for `TARGET_NON_EXPORTABLE_SIGNER`. Preserve that exact tuple in the deployment record for retry/replay. An unknown, unsupported, or not-yet-implemented state fails closed. `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` may be emitted as legacy diagnostic wiring results, but they are never custody proof or player-facing readiness evidence and cannot substitute for the selected custody proof.
7. Record trusted pre-apply bootstrap secret-compliance evidence for each Tier A credential class. First deployment may use immutable initial-provisioning evidence (`lastProvisionedAt`) instead of rotation evidence, but the record must still satisfy the canonical secret-compliance schema before the environment is considered promotable or traffic-open. This evidence proves provisioning and binding only; it does not prove live signer or validator convergence.

Initial pre-apply does not require a JWT rotation drill or post-apply live signer-convergence evidence. `PREFLIGHT-JWT-ROTATION-001` and immutable rotation evidence are event-scoped post-apply gates for first-live, reopen-after-restore, and production promotion evidence, not for this bootstrap apply.

Bootstrap is part of the deployment contract, not an informal prerequisite. A player-facing environment is not considered deployable until this bootstrap pass succeeds with environment-specific credentials and bindings.

## Fresh-Boundary Restore Bootstrap

A restore into a new cluster, new namespace boundary, rebuilt control plane, or replacement hobby host is a fresh-boundary restore. It must run the environment bootstrap contract before restored workloads can be treated as player-facing, even when the target environment name is the same as before the incident.

The restore source may provide PostgreSQL data, selected Kubernetes manifests, and non-secret configuration, but the new boundary must create or re-bind environment-owned trust material before normal workload startup:

1. Create the target namespace and keep it in restore quarantine before any Gateway, TCP Proxy, scheduler, worker, or Game Session tick executor can accept traffic or create new coordination state.
2. Provision registry pull credentials, PostgreSQL admin/application credentials, and exactly one accepted JWT custody state: the interim Account-authorized private-slot backend plus its separate interim public `jwt-jwks` ConfigMap, or target signer reference plus its separate target public `jwt-jwks` ConfigMap. For the interim backend, Account has exclusive mounted/signing use of the private bundle; the materialization controller has only bounded, Account-authorized byte handling needed to materialize it and no competing signing authority. Every private-slot action uses the persisted Account-authorized operation boundary; the materialization controller is execution-only and cannot independently choose slots or mutate desired key-ring state. Do not select restored or newly provisioned legacy Secret-backed JWT/JWKS wiring as fresh-boundary custody; it remains diagnostic drift only. Also provision cert-manager issuer bindings, workload/bridge/operator certificate resources, backup/object-store credentials, asset-store credentials, outbound-communications bindings, and operator credential bindings for the new boundary.
3. Restore PostgreSQL data and manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
4. Re-run `./dev-tools/deploy/preflight.py <environment>` and require the bootstrap, secret, bridge, Redis, external-binding, and service-discovery checks to pass, plus exactly one authenticated custody proof with an exact mode-matching `proofId`, `custodyMode`, and `contractVersion`: `PREFLIGHT-JWT-INTERIM-001` for `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` or `PREFLIGHT-JWT-002` for `TARGET_NON_EXPORTABLE_SIGNER`. Preserve that exact tuple in the fresh-boundary recovery/deployment record and require the same tuple on replay. `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` may be emitted only as legacy diagnostic wiring results; neither is accepted proof or a substitute for the selected proof. An unknown, unsupported, or not-yet-implemented state fails closed before progressing.
5. **Target-only cluster recovery command (currently blocked):** The target command shape is `coordination-maintenance recover --mode reset --scope cluster --invalidate-sessions`, as defined in [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence); do not run it until the required end-to-end cluster recovery proof exists. The cluster selector is the complete Coordination Redis deployment/gameplay-environment boundary, and no restore-specific operation flag is added. No recovery scope is currently implemented and proven, so tooling must reject this command shape until cluster recovery has the required end-to-end proof rather than silently approximating it with a narrower scope. The target Coordination-maintenance flow owns pause/fencing, epoch advancement, Coordination Redis reset, and durable tick/command reconciliation. Account Service attests invalidation of restored Account authority and `game-session-account-delegation` lineages; Game Session attests invalidation of restored gameplay bindings and sessions. Recovery only records and verifies those owner attestations; it is not a second Account or Game Session invalidation writer. Record an empty Redis result only as observed post-reset evidence; key absence is not direct destructive proof and cannot authorize recovery or resume.
6. Run the offline durable-participant and external-effect convergence sequence, then the post-restore hardening flow from `design/architecture/system-architecture-post-restore-hardening.md`.
7. **Target-only recovery/reopen sequence (currently blocked):** Keep the fresh boundary quarantined and hold Gateway, TCP Proxy, normal background processors, outbound integrations, schedulers, and Game Session tick/command intake stopped or behind the restore-safe gate. Do not run continuation or release controls until the canonical [restore mode](./system-architecture-backup-recovery.md#restore-mode-selection), [recovery-controller lifecycle](./system-architecture-backup-recovery.md#recovery-controller-continuation), and [fixed erasure-replay boundary](./system-architecture-backup-recovery.md#artifact-erasure-replay-boundary) are satisfied by owner evidence and the applicable preflight gate. Checked-in recovery and traffic-open projections are post-finalization evidence, not release authority. Until the target controller is implemented and proved, use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) and do not attempt blocked controls. This step retains deployment stop conditions but does not define a second recovery protocol.

Restored snapshot-era Secrets are not authoritative trust material for a fresh-boundary restore. Operators may use restored Secret objects only as temporary inputs to hardening or data recovery, and they must be replaced, rotated, reissued, or explicitly re-bound to the new environment boundary before player traffic reopens.

## Production Traffic-Open Backup Gate

Before opening production to player traffic for the first time, or reopening it after a restore into a fresh environment boundary, operators must prove that recovery already works for the live environment:

1. Confirm the production backup/object-store binding and environment-wide PostgreSQL artifact lineage are the intended production targets.
2. Confirm at least one successful online PostgreSQL backup upload exists. `verify-backups.sh` proves only backup existence and optional object-store reachability; separate immutable evidence must prove artifact integrity, readability, restore-tool compatibility, and readiness.
3. Confirm a production-equivalent `cold_start_restore` drill exists within 30 days. The drill may be isolated in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its controlled reopen cannot authorize production traffic.
4. Confirm the [production traffic-open backup evidence](./system-architecture-backup-recovery-evidence-and-compliance.md#production-traffic-open-backup-evidence): `backupReadinessRef` is a backup-readiness artifact whose `restoreRecoveryRecordRef` is dereferenced and validated independently from `baselineRecoveryRecordRef`. Both record the exact environment-wide restore boundary, observed empty Coordination Redis state after the canonical recovery operation, coordination-maintenance fencing/epoch/reset/reconciliation evidence, Account's authority/delegation invalidation attestation, and Game Session's binding/session invalidation attestation, alongside every gameplay-region epoch/fence reset, authoritative complete validator/participant/external-effect inventories, safe dispositions for every declared and enabled entry, backup confidentiality, post-restore hardening, smoke validation, and controlled reopen. Empty Redis is observational evidence only and is not direct destructive proof or a resume authorization.
5. Confirm backup/recovery tool digests, recovery-contract fingerprint, schema lineage, service digests, participant inventory, and expected bindings match the boundary being opened.
6. For both `first-live` and `reopen`, confirm the durable actual-recovery controller has `phase=ready_to_reopen` and `status=RUNNING`, has `recoveryPurpose=actual-recovery`, uses the event-matching `trafficExposure` (`player-facing-first-live` or `player-facing-reopen`), and names the exact target boundary; any other phase or status fails closed, and the isolated drill projections alone are insufficient.
7. After steps 1-6 have validated the immutable evidence and established the exact durable controller at `phase=ready_to_reopen` and `status=RUNNING`, require `PREFLIGHT-BACKUP-002=pass`. Follow the canonical [recovery-controller lifecycle](./system-architecture-backup-recovery.md#recovery-controller-continuation) for continuation and release; this runbook does not define those calls or phases. Until the controller is implemented and proved, operators must use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) and must not attempt blocked controls. Keep traffic quarantined until the owner-defined finalized success state; checked-in actual-recovery and traffic-open files are post-release evidence only and cannot authorize exposure.

If production must be opened before the normal schedules have accumulated history, operators must create an explicit bootstrap backup, verification, and restore-drill record first. Opening traffic without proven recovery evidence is non-compliant.
This is a traffic-open gate, not a routine steady-state rollout gate.

## Recovery Proof Cadence and Release Reuse

- Run a full production-equivalent recovery drill at least every 30 days.
- For each ordinary rollback-compatible production release, write the compact recovery-compatibility result into promotion/deployment evidence by comparing backup/restore tool compatibility, database and migration restore compatibility, recovery-contract fingerprint, enabled participant inventory, secret/binding contract, and environment binding. Reuse a baseline only when it is fresh, has controller state `phase=finalized` with `status=SUCCEEDED`, and the result proves there are no invalidating recovery-contract changes; do not duplicate the full recovery record.
- A change requires a new drill only when it alters restore compatibility, recovery semantics, the enabled participant contract/inventory, trust or binding contracts, or the backup/restore/hardening/reconciliation path. Restore-compatible additive migrations and routine secret-value rotation may pass the cheap classifier when their contracts are unchanged.
- A `roll-forward-only` release always requires a release-candidate drill that starts from current production database lineage and proves the exact candidate recovery tooling, promoted digests, migration path, config, and bindings.
- First-live and reopen-after-restore require environment-specific proof for the actual boundary being opened; the periodic cadence record alone is insufficient.

## Player-Facing JWT Readiness Gate

After workloads are applied, and before first-live, reopen-after-restore, or production promotion evidence is accepted, operators must require `PREFLIGHT-JWT-ROTATION-001=pass` once the executable emits and validates this event-scoped gate. Until then, its absence blocks player-facing JWT readiness, and operators must prove the [phased JWT rotation contract](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative) while treating executable gate implementation as a prerequisite to this release procedure. This post-apply live convergence gate is intentionally separate from trusted initial bootstrap evidence:

1. Confirm the selected custody mode: `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` permits the asymmetric private signing bundle only in Account Service; target non-exportable signer mode requires no private signing material in any application workload plus signer-health evidence. In both modes, every validator uses Account `kid`/JWKS verification with HMAC fallback disabled.
2. Have Account alone reconcile and advance the production rotation operation through the mode-specific evidence required by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative): target signer-side retirement and hard-cutover evidence, or interim Account-authorized materialization-controller private-slot pruning and separate interim private/public projection evidence. In target state only, the recovery controller persists the operation and evidence, invokes the Account-owned rotation operation, and observes its returned convergence; it cannot select, promote, prune, reconcile, or advance rotation. The rotation-evidence workload is observation-only and may record public-JWKS and bounded validator results. Both modes retain Account-owned JWKS convergence, validator visibility, continuity, and rejection proof.
3. Prove rollback-safe JWKS retention for every key used by either signer generation.
4. Run a quarantined compromise drill that performs environment-wide issuer invalidation, hard cutover without old-key overlap, forced validator convergence, old-`kid` rejection, and replacement-`kid` acceptance.
5. Retain the immutable artifact digest, key generations, exact validator inventory, timings, acceptance/rejection results, and authorized reopen outcome.

The drill evidence may be reused within the configured freshness window only while the rotation artifact digest, key lifecycle contract, complete validator inventory, and environment binding remain unchanged. Mounted Secrets, file watcher callbacks, and raw JWKS serving are insufficient evidence.

## Overlay Deployment Flow (Staging and Production)

1. **Review Release Notes**
   - Confirm which services and schema changes are included.
   - Identify any manual migration or configuration steps called out for operators.
2. **Verify CI/CD Status**
   - Ensure the GitHub Actions pipeline for the target branch and tag is green.
   - Check that container image digests are available in the configured registry.
   - For production releases, confirm the release digest manifest exists and binds the release tag, production deployment reference, production attestation, staging deployment record, and exact service digest set being promoted.
   - Confirm deployment evidence includes rollback-mode classification (`rollback-compatible` or `roll-forward-only`) for the release candidate.
   - Treat rollback compatibility as broader than binary compatibility alone: previous digests must remain safe to re-apply against the current database schema, secret/config contract, mounted file-path contract, and expected external bindings.
3. **Update and Merge the Environment Overlay**
   - Update the image digests in the environment-specific Kustomize overlay for the target environment (for example `k8s/overlays/stage` or `k8s/overlays/prod`) via a Git change.
   - Use a pull request for the overlay change so promotion and rollback remain auditable. The merged commit is the source of truth for what is intended to run in that environment.
   - Check out that exact merged commit in the secure operator environment before preflight. Do not preflight an unmerged branch, a synthetic merge ref, or a later working-tree state.
4. **Run Preflight Policy Checks**
   - Validate the target overlay before apply and fail fast on policy violations.
   - Render and evaluate the exact already-merged overlay commit that will be applied. The preflight report `deploymentRef.overlayCommitSha`, immutable deployment record, namespace annotation or deploy-info ConfigMap, and live-state verification must all bind that same commit.
   - Evaluate the implemented policy IDs from `design/architecture/system-architecture-deploy-preflight-policy.md` (for example `PREFLIGHT-DIGEST-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, `PREFLIGHT-SERVICES-001`, `PREFLIGHT-PROMOTION-001`, and `PREFLIGHT-BACKUP-001` for production). `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` may be included as current legacy diagnostic results only; they are not custody proof or readiness authority. `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, and event-scoped `PREFLIGHT-JWT-ROTATION-001` remain target-state-only until the executable emits them.
   - Treat preflight as blocking. Do not run `kubectl apply` until all checks pass.
   - Use the canonical entrypoint: `./dev-tools/deploy/preflight.py <staging|production|hobby-self-hosted>`.
   - Store the preflight report artifact under `design/operations/deployments/<environment>/preflight/<deployment-ref>/<deploymentEventId>.json`. Preflight generates a new `deploymentEventId` for each concrete run; copy that UUID into the immutable deployment record, apply within 30 minutes of report completion, and generate a new event path for every retry or re-apply. The target-state waiver record is the adjacent `<deploymentEventId>.waiver.json`, but executable waiver use remains blocked until trusted one-time consumption authority exists.
5. **Apply Kubernetes Manifests**
   - From a secure operator environment, apply the overlay (for example `kubectl apply -k k8s/overlays/prod`).
   - Each environment boundary (staging vs production) uses its own cluster credentials and secret sources; `firemud` is the default namespace name within each boundary. When using a non-default namespace for drills or temporary restores, treat that namespace as an explicit override tied to the selected overlay or restore script inputs.
   - For a `first-live` or `reopen` event, treat the apply as an operational action that enacts the already-reviewed overlay change before the durable recovery/deployment controller reaches `phase=finalized` with `status=SUCCEEDED`; keep player-facing traffic quarantined while rollout, recovery, and release gates are evaluated. Routine steady-state rollouts that do not change traffic-open status follow the ordinary deployment preflight and rollout policy instead.
   - For `first-live` and `reopen`, applying manifests, reaching readiness, or completing restore-safe smoke checks does not authorize player-facing exposure. Only controller state with `phase=finalized` and `status=SUCCEEDED`, with quarantine-release postconditions observed, permits exposure. Routine steady-state rollout traffic remains governed by its existing deployment state and ordinary preflight policy rather than requiring an unrelated recovery finalization.
   - Record which overlay commit was applied so “what is deployed?” is answerable even when cluster state drifts:
     - Capture the Git commit SHA and timestamp in the deployment notes/runbook record for the environment.
     - Stamp the SHA into the cluster so it is retrievable during incidents:
       - Preferred: annotate the namespace with the overlay SHA:
         - `kubectl annotate namespace <namespace> firemud.io/overlay-sha=<git-sha> --overwrite`
       - Alternative: create/update a dedicated ConfigMap (for example `firemud-deploy-info`) that stores `overlay_sha` and `applied_at` as data keys.
6. **Verify Live State**
   - Confirm the apply was executed from the exact merged overlay commit used for preflight and review; any commit or working-tree drift invalidates that preflight run.
   - Capture the live workload state after rollout:
     - actual running image digests for updated Deployments/StatefulSets,
     - the namespace overlay SHA annotation (or `firemud-deploy-info` equivalent),
     - rollout completion timestamps,
     - referenced secret/config resource versions required by the release,
     - smoke-test evidence references.
   - Store this verification in the deployment record so promotion evidence reflects what is actually running, not only what was intended.
   - Mark `deployStatus=pass` only after the live-state verification and applicable smoke checks both succeed. For `first-live` and `reopen`, this status does not itself expose player-facing traffic, which remains gated by the durable controller reaching `phase=finalized` with `status=SUCCEEDED`; for routine steady-state rollouts it records deployment success without creating a recovery-controller prerequisite.
7. **Monitor Rollout**
   - Watch deployment rollout status for each updated service.
   - Verify pod readiness and liveness probes are passing.
   - Check logs for startup errors, especially around database connectivity, Redis connectivity, and secrets loading.
8. **Post-Deployment Checks**
   - For `first-live` and `reopen`, run only recovery-safe generic smoke checks while player traffic remains quarantined: workload readiness/liveness, secrets/configuration, service-to-service, Redis/PostgreSQL, and tick/recovery health. Do not include login, session/reconnect, or player-connectivity checks in this pre-finalized smoke evidence.
   - Only after the durable recovery/deployment controller reaches `phase=finalized` with `status=SUCCEEDED` and its quarantine-release postconditions are observed may operators run the login/session checks described in `../developer-workflows/login-session-smoke-tests.md` or confirm Web client/Telnet player connectivity. That successful terminal controller state is the sole player-traffic release boundary; smoke success, readiness, `ready_to_reopen`, `AWAITING_RESUME`, `RESUME_AUTHORIZED`, or deployment status cannot authorize exposure.
9. **Record Rollback Classification**
   - Mark the deployment evidence as `rollback-compatible` when previous digests are safe to re-apply against the current schema, secret/config contract, mounted file-path contract, and external bindings.
   - Mark as `roll-forward-only` when schema, secret/config, file-path, or external-binding changes make old-binary rollback unsafe; include the forward-remediation or restore-point path. In the current implementation, this classification is still useful for staging, rehearsals, and future-ready evidence, but it is not an approvable player-facing production release mode yet.
   - Include the compact `recoveryCompatibility` result or immutable reference in every production promotion/deployment record.
   - For `rollback-compatible` releases, prove reuse compatibility with the current recovery drill without copying the full record. A `drill_required` result blocks promotion until the fresh drill is complete and the compatibility result is regenerated as `compatible`; attaching evidence does not make `drill_required` promotable. A `roll-forward-only` release requires that regenerated compatible result plus `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`; its drill must start from current production database lineage and prove the exact candidate recovery tooling, service digests, migration path, config, bindings, controller lineage, and confidentiality evidence.
   - Current implementation note: the executable intentionally blocks every production promotion class, including `rollback-compatible`, until complete recovery inventory membership and immutable evidence dereferencing are implemented. Continue recording the truthful rollback classification for staging and future-ready evidence, but do not treat any classification as current production promotion authority.
10. **Record Deployment State Authoritatively**

    Write or update the canonical deployment record for the exact deployment event. The deployment record is the source of truth for current deployability status and must include `deploymentEventId`, deployment input reference, preflight report path, live-state evidence, smoke evidence, rollback classification, secret-compliance snapshot, and any backup or traffic-open gate evidence required by the environment. Retries or re-applies update the same deployment-ref lineage with a new event UUID, apply timestamp, preflight report, and evidence rather than relying on ad hoc operator notes or carrying a waiver forward.

## Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)

1. **Resolve Deployment Inputs**
   - Select the exact `manifestRef` or `chartVersion` to deploy.
   - Define rollback target (`previousManifestRef` or `previousChartVersion`) before apply.
2. **Run Operator Preflight**
   - Provide the explicit hobby render input through `FIREMUD_PREFLIGHT_RENDER_PATH`, then run the canonical command `./dev-tools/deploy/preflight.py hobby-self-hosted`; a stage overlay or any other fallback is forbidden for hobby validation.
   - Treat required preflight checks as blocking for player-facing traffic.
   - For the target controller-backed first-live and reopen flow, require `PREFLIGHT-BACKUP-003=pass` before requesting controller continuation. The preflight result is evidence for that request; it does not authorize release or open player traffic.
   - Treat `PREFLIGHT-BACKUP-003` as a required pre-continuation gate for first-live and reopen events, not as a required check for ordinary steady-state hobby rollouts that do not change player-traffic status. Only controller state with `phase=finalized` and `status=SUCCEEDED` may authorize player-traffic exposure.
   - Store the preflight report at `design/operations/deployments/hobby-self-hosted/preflight/<deployment-ref>/<deploymentEventId>.json`. Waiver artifacts use the adjacent target-state path `<deploymentEventId>.waiver.json`, but executable waiver use remains blocked until trusted one-time consumption authority exists.
3. **Apply Manifests/Charts**
   - Apply from a secure operator environment using the chosen manifest/chart input.
   - Use ordinary steady-state replicas and processing for a routine rollout. Hold normal workloads at zero replicas or behind restore-safe traffic quarantine only for `first-live` or `reopen` events until the durable recovery controller authorizes release.
   - Record the applied `manifestRef`/`chartVersion`, timestamp, and operator identity in deployment evidence.
4. **Monitor Rollout and Run Smoke Checks**
   - Verify pod readiness/liveness, secrets loading, and Redis/PostgreSQL connectivity.
   - For `first-live` and `reopen`, keep generic smoke limited to recovery-safe service and tick checks while traffic is quarantined; exclude login, session/reconnect, and player-connectivity checks until the controller reaches `phase=finalized` with `status=SUCCEEDED`. Run those player-facing checks only as post-success-finalization release verification.
5. **Record Deployment Evidence**
   - Record deployment evidence at `design/operations/deployments/hobby-self-hosted/deployments/<deployment-ref>/<deploymentEventId>.json`.
   - Include: deployment input reference, preflight report path, live-state verification summary, smoke evidence references, rollback reference, and any backup/open-traffic gate evidence required to reopen player traffic.

## Canary or Phased Rollouts

For higher-risk changes:

- Deploy to a non-production environment and run extended smoke, load, and gameplay tests.
- Use Kubernetes deployment strategies (for example `RollingUpdate` with conservative surge/unavailable settings) to minimize impact.

## Rollback

If a deployment causes instability:

- Evaluate rollback mode from deployment evidence before taking action:
  - `rollback-compatible`: roll back to the previous known-good digest set for affected services only when that release is still compatible with the current schema, secret/config contract, mounted file-path contract, and external bindings.
  - `roll-forward-only`: do not re-apply old binaries; execute the documented forward remediation or restore-point recovery path.
- If database schema changes were applied, follow the guidance in `design/architecture/system-architecture-database-migrations.md` for downgrade or compatibility handling.
- Use the Redis and tick system runbooks to recover any affected coordination state, ensuring idempotent replay where required.

## Related Runbooks

- `design/architecture/system-architecture-scaling-runbook.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-asset-store-runbook.md`
- `design/architecture/system-architecture-telnet-degraded-runbook.md`

---

## Per-Environment Deployment & Rollback Summary

| Environment | Deploy Steps | Rollback Steps |
| --- | --- | --- |
| **Staging** | Ensure CI is green → run preflight policy checks (including bootstrap and external-binding checks for player-facing invariants) → open/merge PR that updates image digests in `k8s/overlays/stage` → apply overlay: `kubectl apply -k k8s/overlays/stage` → verify live state matches the merged overlay → monitor rollout and run smoke tests → write/update the canonical deployment record | Open/merge PR that reverts `k8s/overlays/stage` to the last known-good digest set → re-apply overlay → verify live state → monitor rollout |
| **Production** | Merge the `release-please` release PR to `main` and confirm the release tag (for example `v1.2.3`) exists → ensure CI and security scans are green → verify staging attestation for the exact digest set → run preflight including recovery-evidence compatibility → for `roll-forward-only` releases require an exact release-candidate recovery drill → for first-live or reopen events require environment-specific online-backup, verification, complete `cold_start_restore`, controller-lineage, and confidentiality proof within 30 days → open/merge PR that updates `k8s/overlays/prod` to the approved digests → apply overlay: `kubectl apply -k k8s/overlays/prod` → verify live state matches the merged overlay → monitor rollout and run smoke tests → write/update the canonical deployment record | Open/merge PR that reverts `k8s/overlays/prod` to the last known-good digest set → re-apply overlay → verify live state → monitor rollout; follow database migration downgrade guidance when schema changes are involved |
| **Hobby / Self-Hosted** | Resolve target manifests/charts → provide `FIREMUD_PREFLIGHT_RENDER_PATH` and run the canonical operator preflight (`./dev-tools/deploy/preflight.py hobby-self-hosted`), with no stage-overlay fallback, then capture the partial/static-only report → apply manifests/charts from the operator environment with normal steady-state processing; only for first-live or reopen after restore hold normal workloads at zero or behind restore-safe traffic quarantine → verify live state, monitor rollout, and run recovery-safe generic smoke only (no login, session/reconnect, or player-connectivity checks before finalization) → for first-live or reopen events do not treat `PREFLIGHT-BACKUP-003` as traffic authorization; keep traffic closed and follow the canonical [Backup & Disaster Recovery recovery lifecycle](./system-architecture-backup-recovery.md#recovery-controller-continuation) until its owner-defined finalized success state → expose traffic only after that terminal state, then run player-facing connectivity verification and record canonical deployment evidence (`manifestRef`/`chartVersion`, preflight report, rollback reference) | Re-apply previously known-good manifest/chart reference and confirm health only when the prior release remains compatible with the current schema, secret/config contract, mounted file-path contract, and external bindings; if not, follow the documented forward remediation path |

Overlay PRs should include a clear deployment intent payload: target environment, service image digests, source commit/tag, rollback digest set (or explicit `roll-forward-only` marker), and (for production) an attestation reference under `design/operations/deployments/production/attestations/`. Attestation schema and validation requirements are defined in `design/architecture/system-architecture-promotion-attestation.md`. CI validates overlay images and preflight policy contracts via [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml) before merge.
