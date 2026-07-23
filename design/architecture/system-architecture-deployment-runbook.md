# FireMUD Deployment Runbook

This runbook describes the **standard deployment flow** for FireMUD in Kubernetes-backed environments.

For high-level CI/CD architecture, see `design/architecture/system-architecture-cicd.md`. This document focuses on the concrete steps and checks an operator performs when rolling out a new version.

## Prerequisites

- CI pipeline has produced immutable image digests for each service to deploy.
- Database migrations have been validated (see `design/architecture/system-architecture-database-migrations.md`).
- Redis, PostgreSQL, and core infrastructure components (Gateway, TCP Proxy, Observability stack) are healthy.
- The operator has `kubectl` access and a kubeconfig for the target Kubernetes cluster (staging, production, or hobby-self-hosted) from a secure admin workstation or bastion host.

## Implementation Notes

This runbook describes the required deployment flow. Current automation is executable for the existing expected-binding report reference, mounted JWT/JWKS path contract, the hobby traffic-open evidence gate, and the production fail-closed placeholder, but some required gates and evidence production remain operator- or future-automation-owned:

- Fresh-boundary restore bootstrap and post-restore secret-compliance refresh are canonical requirements, but current restore scripts do not yet automate the full evidence chain.
- Current backup/preflight automation does not prove environment-wide artifact lineage, enforced quarantine, empty-Redis cold start, complete recovery-participant convergence, or controlled reopen. Production first-live, post-rewind reopen, and `roll-forward-only` promotion remain blocked until that proof exists.
- Production release digest manifests are canonical release-lineage evidence, but current overlay CI does not yet enforce their presence or schema.
- Expected-binding validation is first-pass repository/render validation. Real first-live and reopen decisions require current environment evidence and the durable controller authority described below; checked-in projection files cannot authorize the release transaction.
- `PREFLIGHT-JWT-002` and `PREFLIGHT-JWT-ROTATION-001` are not yet implemented, so the current shared-HMAC and private-key distribution topology cannot satisfy player-facing JWT readiness.

Operators must treat missing real-environment evidence as a blocker even when static preflight policy IDs are present. A successful static report without the required traffic-open, restore, release-manifest, and secret-compliance evidence is not enough to open player-facing traffic.

## Environment Bootstrap (First Deployment Only)

Before the first player-facing deployment into `hobby-self-hosted`, `staging`, or `production`, operators must complete a bootstrap step that creates the minimum environment trust and secret set before any workload apply:

1. Provision the target namespace and registry pull credentials used by workloads.
2. Provision per-environment PostgreSQL credentials (`postgres-credentials`) and, when rotation Jobs are used, `postgres-admin-credentials`.
3. Provision a per-environment asymmetric Account signing bundle (`jwt-signing-keys`) mounted only into Account Service and a public validation resource (`jwt-jwks`) consumed by every validator.
4. Provision cert-manager issuer bindings and certificate resources required for workload gRPC mTLS, Gateway internal mTLS WebSocket listener, TCP Proxy bridge mTLS client identity, and operator-only client identities where applicable.
5. Provision per-environment external integration credentials: backup/object-store, asset-store, outbound-communications, and operator-control-plane credentials as needed for that environment class.
6. Run `./dev-tools/deploy/preflight.py <environment>` and require `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-JWT-001`, `PREFLIGHT-JWKS-001`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` to pass before the first apply. Target-state `PREFLIGHT-JWT-002` becomes part of this bootstrap gate only after the executable emits and proves it.
7. Record bootstrap secret-compliance evidence for each Tier A credential class. First deployment may use immutable initial-provisioning evidence (`lastProvisionedAt`) instead of rotation evidence, but the record must still satisfy the canonical secret-compliance schema before the environment is considered promotable or traffic-open.

Initial pre-apply does not require a JWT rotation drill or rotation evidence. `PREFLIGHT-JWT-ROTATION-001` and immutable rotation evidence are event-scoped gates for first-live, reopen-after-restore, and production promotion evidence, not for this bootstrap apply.

Bootstrap is part of the deployment contract, not an informal prerequisite. A player-facing environment is not considered deployable until this bootstrap pass succeeds with environment-specific credentials and bindings.

## Fresh-Boundary Restore Bootstrap

A restore into a new cluster, new namespace boundary, rebuilt control plane, or replacement hobby host is a fresh-boundary restore. It must run the environment bootstrap contract before restored workloads can be treated as player-facing, even when the target environment name is the same as before the incident.

The restore source may provide PostgreSQL data, selected Kubernetes manifests, and non-secret configuration, but the new boundary must create or re-bind environment-owned trust material before normal workload startup:

1. Create the target namespace and keep it in restore quarantine before any Gateway, TCP Proxy, scheduler, worker, or Game Session tick executor can accept traffic or create new coordination state.
2. Provision registry pull credentials, PostgreSQL admin/application credentials, JWT signing/JWKS resources, cert-manager issuer bindings, workload/bridge/operator certificate resources, backup/object-store credentials, asset-store credentials, outbound-communications bindings, and operator credential bindings for the new boundary.
3. Restore PostgreSQL data and manifests with normal application workloads held at zero replicas or behind a restore-safe startup gate.
4. Re-run `./dev-tools/deploy/preflight.py <environment>` and require the bootstrap, secret, JWT/JWKS, bridge, Redis, external-binding, and service-discovery checks to pass for the new boundary before progressing.
5. Replace or clear Coordination Redis, prove it is empty, invalidate all restored gameplay and Account sessions, and advance or recreate every gameplay-region epoch and fence.
6. Run the offline durable-participant and external-effect convergence sequence, then the post-restore hardening flow from `design/architecture/system-architecture-post-restore-hardening.md`.
7. Refresh the environment secret-compliance record, complete every pre-release recovery and confidentiality control, capture immutable `restoreHighWater`, and replay the gap-free erasure interval from `artifactErasureHighWater`. Record approval in the durable recovery controller, then call `continueRecovery(operationId, expectedPhase, evidenceRef)` for the current durable phase; the controller must reach `ready_to_reopen`, reconcile its internal `ready_to_reopen -> releasing -> finalized` phases, apply and observe the quarantine release before traffic opens, and export the checked-in evidence projection only afterward.

Restored snapshot-era Secrets are not authoritative trust material for a fresh-boundary restore. Operators may use restored Secret objects only as temporary inputs to hardening or data recovery, and they must be replaced, rotated, reissued, or explicitly re-bound to the new environment boundary before player traffic reopens.

## Production Traffic-Open Backup Gate

Before opening production to player traffic for the first time, or reopening it after a restore into a fresh environment boundary, operators must prove that recovery already works for the live environment:

1. Confirm the production backup/object-store binding and environment-wide PostgreSQL artifact lineage are the intended production targets.
2. Confirm at least one successful online PostgreSQL backup upload exists. `verify-backups.sh` proves only backup existence and optional object-store reachability; separate immutable evidence must prove artifact integrity, readability, restore-tool compatibility, and readiness.
3. Confirm a production-equivalent `cold_start_restore` drill exists within 30 days. The drill may be isolated in a production-equivalent boundary using current production database lineage and compatible recovery contracts/tooling; its controlled reopen cannot authorize production traffic.
4. Confirm `backupReadinessRef` is a backup-readiness artifact whose `restoreRecoveryRecordRef` is dereferenced and validated independently from `baselineRecoveryRecordRef`. Both prove empty Coordination Redis, environment-wide gameplay and Account session invalidation, every gameplay-region epoch/fence reset, authoritative complete validator/participant/external-effect inventories, safe dispositions for every declared and enabled entry, backup confidentiality, post-restore hardening, smoke validation, and controlled reopen.
5. Confirm backup/recovery tool digests, recovery-contract fingerprint, schema lineage, service digests, participant inventory, and expected bindings match the boundary being opened.
6. For `reopen`, confirm the durable actual-recovery controller is `ready_to_reopen`, has `recoveryPurpose=actual-recovery` and `trafficExposure=player-facing-reopen`, and names the exact target boundary; the isolated baseline projection alone is insufficient.
7. Call `continueRecovery(operationId, expectedPhase, evidenceRef)` to record the runtime authorization in the durable recovery controller and require `PREFLIGHT-BACKUP-002=pass`. The controller then idempotently reconciles its internal `ready_to_reopen -> releasing -> finalized` phases, applies and observes quarantine release, and only afterward permits player traffic; checked-in actual-recovery and traffic-open projections are exported after `finalized`.

If production must be opened before the normal schedules have accumulated history, operators must create an explicit bootstrap backup, verification, and restore-drill record first. Opening traffic without proven recovery evidence is non-compliant.
This is a traffic-open gate, not a routine steady-state rollout gate.

## Recovery Proof Cadence and Release Reuse

- Run a full production-equivalent recovery drill at least every 30 days.
- For each ordinary rollback-compatible production release, write the compact recovery-compatibility result into promotion/deployment evidence by comparing backup/restore tool compatibility, database and migration restore compatibility, recovery-contract fingerprint, enabled participant inventory, secret/binding contract, and environment binding. Reuse a baseline only when it is fresh, finalized, and the result proves there are no invalidating recovery-contract changes; do not duplicate the full recovery record.
- A change requires a new drill only when it alters restore compatibility, recovery semantics, the enabled participant contract/inventory, trust or binding contracts, or the backup/restore/hardening/reconciliation path. Restore-compatible additive migrations and routine secret-value rotation may pass the cheap classifier when their contracts are unchanged.
- A `roll-forward-only` release always requires a release-candidate drill that starts from current production database lineage and proves the exact candidate recovery tooling, promoted digests, migration path, config, and bindings.
- First-live and reopen-after-restore require environment-specific proof for the actual boundary being opened; the periodic cadence record alone is insufficient.

## Player-Facing JWT Readiness Gate

At first-live, reopen-after-restore, or production promotion evidence acceptance, operators must require `PREFLIGHT-JWT-ROTATION-001=pass` and prove the [phased JWT rotation contract](./system-architecture-security.md#jwt-key--jwks-rotation-workflow). This gate is intentionally separate from initial bootstrap pre-apply:

1. Confirm only Account Service receives the asymmetric private signing bundle and every validator uses Account `kid`/JWKS verification with HMAC fallback disabled.
2. Run the production rotation artifact through prepublication, validator visibility, signer promotion, old/new continuity, overlap through retiring-token expiry, pruning, and rejection proof.
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
3. **Run Preflight Policy Checks**
   - Validate the target overlay before apply and fail fast on policy violations.
   - Evaluate the implemented policy IDs from `design/architecture/system-architecture-deploy-preflight-policy.md` (for example `PREFLIGHT-DIGEST-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-JWT-001`, `PREFLIGHT-JWKS-001`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, `PREFLIGHT-SERVICES-001`, `PREFLIGHT-PROMOTION-001`, and `PREFLIGHT-BACKUP-001` for production). `PREFLIGHT-JWT-002` and event-scoped `PREFLIGHT-JWT-ROTATION-001` remain target-state-only until the executable and contract tests emit them.
   - Treat preflight as blocking. Do not run `kubectl apply` until all checks pass.
   - Use the canonical entrypoint: `./dev-tools/deploy/preflight.py <staging|production|hobby-self-hosted>`.
   - Store the preflight report artifact under `design/operations/deployments/<environment>/preflight/<deployment-ref>/<deploymentEventId>.json`. Preflight generates a new `deploymentEventId` for each concrete run; copy that UUID into the immutable deployment record, apply within 30 minutes of report completion, and generate a new event path for every retry or re-apply. The target-state waiver record is the adjacent `<deploymentEventId>.waiver.json`, but executable waiver use remains blocked until trusted one-time consumption authority exists.
4. **Update the Environment Overlay (Git-Tracked)**
   - Update the image digests in the environment-specific Kustomize overlay for the target environment (for example `k8s/overlays/stage` or `k8s/overlays/prod`) via a Git change.
   - Use a pull request for the overlay change so promotion and rollback remain auditable (the merged commit is the source of truth for what is intended to be running in that environment).
5. **Apply Kubernetes Manifests**
   - From a secure operator environment, apply the overlay (for example `kubectl apply -k k8s/overlays/prod`).
   - Each environment boundary (staging vs production) uses its own cluster credentials and secret sources; `firemud` is the default namespace name within each boundary. When using a non-default namespace for drills or temporary restores, treat that namespace as an explicit override tied to the selected overlay or restore script inputs.
   - Treat the apply as an operational action that enacts the already-reviewed overlay change.
   - Record which overlay commit was applied so “what is deployed?” is answerable even when cluster state drifts:
     - Capture the Git commit SHA and timestamp in the deployment notes/runbook record for the environment.
     - Stamp the SHA into the cluster so it is retrievable during incidents:
       - Preferred: annotate the namespace with the overlay SHA:
         - `kubectl annotate namespace <namespace> firemud.io/overlay-sha=<git-sha> --overwrite`
       - Alternative: create/update a dedicated ConfigMap (for example `firemud-deploy-info`) that stores `overlay_sha` and `applied_at` as data keys.
6. **Verify Live State**
   - Confirm the apply was executed from the exact merged overlay commit used for preflight and review.
   - Capture the live workload state after rollout:
     - actual running image digests for updated Deployments/StatefulSets,
     - the namespace overlay SHA annotation (or `firemud-deploy-info` equivalent),
     - rollout completion timestamps,
     - referenced secret/config resource versions required by the release,
     - smoke-test evidence references.
   - Store this verification in the deployment record so promotion evidence reflects what is actually running, not only what was intended.
   - Mark `deployStatus=pass` only after the live-state verification and smoke checks both succeed.
7. **Monitor Rollout**
   - Watch deployment rollout status for each updated service.
   - Verify pod readiness and liveness probes are passing.
   - Check logs for startup errors, especially around database connectivity, Redis connectivity, and secrets loading.
8. **Post-Deployment Checks**
   - Run smoke tests and login/session checks as described in `design/developer-workflows/login-session-smoke-tests.md`.
   - Confirm that the game session tick loop is running and that players can connect via both Web client and Telnet.
9. **Record Rollback Classification**
   - Mark the deployment evidence as `rollback-compatible` when previous digests are safe to re-apply against the current schema, secret/config contract, mounted file-path contract, and external bindings.
   - Mark as `roll-forward-only` when schema, secret/config, file-path, or external-binding changes make old-binary rollback unsafe; include the forward-remediation or restore-point path. In the current implementation, this classification is still useful for staging, rehearsals, and future-ready evidence, but it is not an approvable player-facing production release mode yet.
   - Include the compact `recoveryCompatibility` result or immutable reference in every production promotion/deployment record.
   - For `rollback-compatible` releases, prove reuse compatibility with the current recovery drill without copying the full record. For `drill_required` or `roll-forward-only` releases, attach `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`; the `roll-forward-only` drill must start from current production database lineage and prove the exact candidate recovery tooling, service digests, migration path, config, bindings, controller lineage, and confidentiality evidence.
   - Current implementation note: the executable intentionally blocks every production promotion class, including `rollback-compatible`, until complete recovery inventory membership and immutable evidence dereferencing are implemented. Continue recording the truthful rollback classification for staging and future-ready evidence, but do not treat any classification as current production promotion authority.
10. **Record Deployment State Authoritatively**

    Write or update the canonical deployment record for the exact deployment event. The deployment record is the source of truth for current deployability status and must include `deploymentEventId`, deployment input reference, preflight report path, live-state evidence, smoke evidence, rollback classification, secret-compliance snapshot, and any backup or traffic-open gate evidence required by the environment. Retries or re-applies update the same deployment-ref lineage with a new event UUID, apply timestamp, preflight report, and evidence rather than relying on ad hoc operator notes or carrying a waiver forward.

## Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)

1. **Resolve Deployment Inputs**
   - Select the exact `manifestRef` or `chartVersion` to deploy.
   - Define rollback target (`previousManifestRef` or `previousChartVersion`) before apply.
2. **Run Operator Preflight**
   - Run `./dev-tools/deploy/preflight.py hobby-self-hosted`.
   - Treat required preflight checks as blocking for player-facing traffic.
   - For first-live opens and reopen-after-restore events, require `PREFLIGHT-BACKUP-003=pass` before requesting controller release and opening player traffic.
   - Treat `PREFLIGHT-BACKUP-003` as a traffic-open gate for first-live and reopen events, not as a required check for ordinary steady-state hobby rollouts that do not change player-traffic status.
   - Store the preflight report at `design/operations/deployments/hobby-self-hosted/preflight/<deployment-ref>/<deploymentEventId>.json`. Waiver artifacts use the adjacent target-state path `<deploymentEventId>.waiver.json`, but executable waiver use remains blocked until trusted one-time consumption authority exists.
3. **Apply Manifests/Charts**
   - Apply from a secure operator environment using the chosen manifest/chart input.
   - Record the applied `manifestRef`/`chartVersion`, timestamp, and operator identity in deployment evidence.
4. **Monitor Rollout and Run Smoke Checks**
   - Verify pod readiness/liveness, secrets loading, and Redis/PostgreSQL connectivity.
   - Run login/session smoke checks and confirm player connectivity paths.
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
| **Hobby / Self-Hosted** | Resolve target manifests/charts → run operator preflight (`./dev-tools/deploy/preflight.py hobby-self-hosted`) and capture report → for first-live or reopen events require backup-baseline compliance evidence (`PREFLIGHT-BACKUP-003`) before opening traffic → apply manifests/charts from operator environment → verify live state → monitor rollout and run smoke tests → record canonical deployment evidence (`manifestRef`/`chartVersion`, preflight report, rollback reference) | Re-apply previously known-good manifest/chart reference and confirm health only when the prior release remains compatible with the current schema, secret/config contract, mounted file-path contract, and external bindings; if not, follow the documented forward remediation path |

Overlay PRs should include a clear deployment intent payload: target environment, service image digests, source commit/tag, rollback digest set (or explicit `roll-forward-only` marker), and (for production) an attestation reference under `design/operations/deployments/production/attestations/`. Attestation schema and validation requirements are defined in `design/architecture/system-architecture-promotion-attestation.md`. CI validates overlay images and preflight policy contracts via [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml) before merge.
