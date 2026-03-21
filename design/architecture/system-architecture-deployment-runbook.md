# FireMUD Deployment Runbook

This runbook describes the **standard deployment flow** for FireMUD in Kubernetes-backed environments.

For high-level CI/CD architecture, see `design/architecture/system-architecture-cicd.md`. This document focuses on the concrete steps and checks an operator performs when rolling out a new version.

## Prerequisites

- CI pipeline has produced immutable image digests for each service to deploy.
- Database migrations have been validated (see `design/architecture/system-architecture-database-migrations.md`).
- Redis, PostgreSQL, and core infrastructure components (Gateway, TCP Proxy, Observability stack) are healthy.
- The operator has `kubectl` access and a kubeconfig for the target Kubernetes cluster (staging, production, or hobby-self-hosted) from a secure admin workstation or bastion host.

## Environment Bootstrap (First Deployment Only)

Before the first player-facing deployment into `hobby-self-hosted`, `staging`, or `production`, operators must complete a bootstrap step that creates the minimum environment trust and secret set before any workload apply:

1. Provision the target namespace and registry pull credentials used by workloads.
2. Provision per-environment PostgreSQL credentials (`postgres-credentials`) and, when rotation Jobs are used, `postgres-admin-credentials`.
3. Provision per-environment JWT resources (`jwt-signing-keys`, `jwt-jwks`) and ensure the Account Service file-path contract can mount them.
4. Provision cert-manager issuer bindings and certificate resources required for workload gRPC mTLS, Gateway internal mTLS WebSocket listener, TCP Proxy bridge mTLS client identity, and operator-only client identities where applicable.
5. Provision per-environment external integration credentials: backup/object-store, asset-store, outbound-communications, and operator-control-plane credentials as needed for that environment class.
6. Run `./dev-tools/deploy/preflight.sh <environment>` and require `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-JWT-001`, `PREFLIGHT-JWKS-001`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` to pass before the first apply.
7. Record bootstrap secret-compliance evidence for each Tier A credential class. First deployment may use immutable initial-provisioning evidence (`lastProvisionedAt`) instead of rotation evidence, but the record must still satisfy the canonical secret-compliance schema before the environment is considered promotable or traffic-open.

Bootstrap is part of the deployment contract, not an informal prerequisite. A player-facing environment is not considered deployable until this bootstrap pass succeeds with environment-specific credentials and bindings.

## Production Traffic-Open Backup Gate

Before opening production to player traffic for the first time, or reopening it after a restore into a fresh environment boundary, operators must prove that recovery already works for the live environment:

1. Confirm the production backup/object-store binding is the intended production target.
2. Confirm at least one successful PostgreSQL logical backup upload exists for the environment.
3. Confirm at least one successful backup verification run exists for the environment.
4. Confirm a successful restore drill exists for the same production environment class/binding and that the drill completed within the required restore-proof freshness window of 30 days.
5. Confirm the referenced backup attempt uses canonical coordinated-backup scope (`tenant_id + region_id`).
6. Record this evidence in the deployment record and require `PREFLIGHT-BACKUP-002=pass` before opening player traffic.

If production must be opened before the normal schedules have accumulated history, operators must create an explicit bootstrap backup, verification, and restore-drill record first. Opening traffic without proven recovery evidence is non-compliant.
This is a traffic-open gate, not a routine steady-state rollout gate.

## Overlay Deployment Flow (Staging and Production)

1. **Review Release Notes**
   - Confirm which services and schema changes are included.
   - Identify any manual migration or configuration steps called out for operators.
2. **Verify CI/CD Status**
   - Ensure the GitHub Actions pipeline for the target branch and tag is green.
   - Check that container image digests are available in the configured registry.
   - Confirm deployment evidence includes rollback-mode classification (`rollback-compatible` or `roll-forward-only`) for the release candidate.
   - Treat rollback compatibility as broader than binary compatibility alone: previous digests must remain safe to re-apply against the current database schema, secret/config contract, mounted file-path contract, and expected external bindings.
3. **Run Preflight Policy Checks**
   - Validate the target overlay before apply and fail fast on policy violations.
   - Evaluate policy IDs from `design/architecture/system-architecture-deploy-preflight-policy.md` (for example `PREFLIGHT-DIGEST-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-SECRETS-002`, `PREFLIGHT-JWT-001`, `PREFLIGHT-JWKS-001`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, `PREFLIGHT-SERVICES-001`, and `PREFLIGHT-PROMOTION-001` for production).
   - Treat preflight as blocking. Do not run `kubectl apply` until all checks pass.
   - Use the canonical entrypoint: `./dev-tools/deploy/preflight.sh <staging|production|hobby-self-hosted>`.
   - Store the preflight report artifact under `design/operations/deployments/<environment>/preflight/<deployment-ref>.json` with optional waiver record `.../<deployment-ref>.waiver.json` as defined in `design/architecture/system-architecture-deploy-preflight-policy.md`.
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
   - Mark as `roll-forward-only` when schema, secret/config, file-path, or external-binding changes make old-binary rollback unsafe; include the forward-remediation or restore-point path.
   - For `roll-forward-only` production releases, attach the fresh backup-readiness evidence record from `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`.
   - Ensure the backup-readiness record is explicitly bound to the production attestation path and promoted digest set before attach.
   - Current implementation note: while player-facing coordinated-backup readiness remains incomplete for canonical `tenant_id + region_id` scope, player-facing production `roll-forward-only` releases are not approvable. Record `rollback-compatible` or stop the promotion; do not approve on the theory that restore-point recovery will cover it later.
10. **Record Deployment State Authoritatively**

    Write or update the canonical deployment record for the exact deployment event. The deployment record is the source of truth for current deployability status and must include deployment input reference, preflight report path, live-state evidence, smoke evidence, rollback classification, secret-compliance snapshot, and any backup or traffic-open gate evidence required by the environment. Retries or re-applies must update the same deployment record lineage with a new apply timestamp and new evidence rather than relying on ad hoc operator notes.

## Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)

1. **Resolve Deployment Inputs**
   - Select the exact `manifestRef` or `chartVersion` to deploy.
   - Define rollback target (`previousManifestRef` or `previousChartVersion`) before apply.
2. **Run Operator Preflight**
   - Run `./dev-tools/deploy/preflight.sh hobby-self-hosted`.
   - Treat required preflight checks as blocking for player-facing traffic.
   - For first-live opens and reopen-after-restore events, require `PREFLIGHT-BACKUP-003=pass` before player traffic is opened.
   - Treat `PREFLIGHT-BACKUP-003` as a traffic-open gate for first-live and reopen events, not as a required check for ordinary steady-state hobby rollouts that do not change player-traffic status.
   - Store preflight report and optional waiver artifacts using the same evidence path contract: `design/operations/deployments/hobby-self-hosted/preflight/<deployment-ref>.json` and `.../<deployment-ref>.waiver.json`.
3. **Apply Manifests/Charts**
   - Apply from a secure operator environment using the chosen manifest/chart input.
   - Record the applied `manifestRef`/`chartVersion`, timestamp, and operator identity in deployment evidence.
4. **Monitor Rollout and Run Smoke Checks**
   - Verify pod readiness/liveness, secrets loading, and Redis/PostgreSQL connectivity.
   - Run login/session smoke checks and confirm player connectivity paths.
5. **Record Deployment Evidence**
   - Record deployment evidence at `design/operations/deployments/hobby-self-hosted/deployments/<deployment-ref>.json`.
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
| **Production** | Merge the `release-please` release PR to `main` and confirm the release tag (for example `v1.2.3`) exists → ensure CI and security scans are green → verify staging attestation for the exact digest set → run preflight policy checks → for `roll-forward-only` releases attach fresh backup-readiness evidence → for first-live or reopen events require proven backup upload, verification, restore-drill evidence within 30 days, and canonical `tenant_id + region_id` coordinated-backup scope → open/merge PR that updates `k8s/overlays/prod` to the approved digests → apply overlay: `kubectl apply -k k8s/overlays/prod` → verify live state matches the merged overlay → monitor rollout and run smoke tests → write/update the canonical deployment record | Open/merge PR that reverts `k8s/overlays/prod` to the last known-good digest set → re-apply overlay → verify live state → monitor rollout; follow database migration downgrade guidance when schema changes are involved |
| **Hobby / Self-Hosted** | Resolve target manifests/charts → run operator preflight (`./dev-tools/deploy/preflight.sh hobby-self-hosted`) and capture report → for first-live or reopen events require backup-baseline compliance evidence (`PREFLIGHT-BACKUP-003`) before opening traffic → apply manifests/charts from operator environment → verify live state → monitor rollout and run smoke tests → record canonical deployment evidence (`manifestRef`/`chartVersion`, preflight report, rollback reference) | Re-apply previously known-good manifest/chart reference and confirm health only when the prior release remains compatible with the current schema, secret/config contract, mounted file-path contract, and external bindings; if not, follow the documented forward remediation path |

Overlay PRs should include a clear deployment intent payload: target environment, service image digests, source commit/tag, rollback digest set (or explicit `roll-forward-only` marker), and (for production) an attestation reference under `design/operations/deployments/production/attestations/`. Attestation schema and validation requirements are defined in `design/architecture/system-architecture-promotion-attestation.md`. CI validates overlay images and preflight policy contracts via [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml) before merge.
